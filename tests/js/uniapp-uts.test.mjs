import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const root = new URL('../../', import.meta.url);
const bridge = await readFile(new URL('adapters/uniapp/bridge.js', root), 'utf8');
const bridgeUrl = 'data:text/javascript;base64,' + Buffer.from(bridge).toString('base64');
let sequence = 0;

async function loadTransport(sourcePath, native) {
  let source = await readFile(new URL(sourcePath, root), 'utf8');
  source = source.replaceAll("'./bridge.js'", JSON.stringify(bridgeUrl));
  source = source.replace(
    "import { invokeSfioraNative } from '@/uni_modules/Sandrox-Sfiora';",
    'const invokeSfioraNative = globalThis.__sfioraTestTransport;'
  );
  globalThis.__sfioraTestTransport = native;
  try {
    return await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64') + `#${++sequence}`);
  } finally {
    delete globalThis.__sfioraTestTransport;
  }
}

test('UTS JS transport preserves all public native methods, arguments and cancellation values', async () => {
  const calls = [];
  const api = await loadTransport('uni_modules/Sandrox-Sfiora/js_sdk/index.js', (method, json, callback) => {
    calls.push([method, JSON.parse(json)]);
    callback(JSON.stringify({ok: true, data: method.startsWith('is') ? true : {method}}));
    callback(JSON.stringify({ok: false, error: {code: 'USER_CANCELLED', message: 'late callback'}}));
  });
  const message = {records: [{kind: 'text', text: 'generic', languageCode: 'en'}]};
  const marker = {domain: 'example.org', type: 'sample'};
  const options = {timeoutMilliseconds: 5000};
  assert.deepEqual(await api.getCapabilities(), {method: 'getCapabilities'});
  assert.deepEqual(await api.startScan(options), {method: 'startScan'});
  assert.equal(await api.isScanning(), true);
  assert.equal(await api.cancelScan(), undefined);
  assert.deepEqual(await api.writeNdef(message, options), {method: 'writeNdef'});
  assert.deepEqual(await api.initializeNdef(message, marker, options), {method: 'initializeNdef'});
  assert.deepEqual(await api.formatNdef(options), {method: 'formatNdef'});
  assert.equal(await api.isWriting(), true);
  assert.equal(await api.cancelWrite(), undefined);
  assert.deepEqual(await api.acquireForegroundDispatch('screen:1'), {method: 'acquireForegroundDispatch'});
  assert.deepEqual(await api.releaseForegroundDispatch('screen:1'), {method: 'releaseForegroundDispatch'});
  assert.deepEqual(await api.getForegroundDispatchState(), {method: 'getForegroundDispatchState'});
  assert.deepEqual(await api.getPresentationState(), {method: 'getPresentationState'});
  assert.equal(await api.waitForPresentationEnd(options), undefined);
  assert.deepEqual(calls, [
    ['getCapabilities', []], ['startScan', [options]], ['isScanning', []], ['cancelScan', []],
    ['writeNdef', [message, options]], ['initializeNdef', [message, marker, options]],
    ['formatNdef', [options]], ['isWriting', []], ['cancelWrite', []],
    ['acquireForegroundDispatch', ['screen:1']], ['releaseForegroundDispatch', ['screen:1']],
    ['getForegroundDispatchState', []], ['getPresentationState', []], ['waitForPresentationEnd', [options]],
  ]);
});

test('UTS JS transport rejects malformed JSON and preserves structured NFC errors', async () => {
  const api = await loadTransport('uni_modules/Sandrox-Sfiora/js_sdk/index.js', (_method, _json, callback) => {
    queueMicrotask(() => callback('invalid JSON'));
  });
  await assert.rejects(api.getCapabilities(), {name: 'SfioraError', code: 'INTERNAL_ERROR'});
  const error = {code: 'USER_CANCELLED', message: 'Cancelled after write; tag may have changed', recoverable: true};
  const failed = await loadTransport('uni_modules/Sandrox-Sfiora/js_sdk/index.js', (_method, _json, callback) => {
    queueMicrotask(() => callback(JSON.stringify({ok: false, error})));
  });
  await assert.rejects(failed.writeNdef({records: []}), error);
  await assert.rejects(failed.startScan([]), {code: 'INVALID_OPTIONS'});
});

test('classic wrapper discovers the runtime when called after module loading', async () => {
  const api = await loadTransport('adapters/uniapp/index.js');
  globalThis.uni = {requireNativePlugin: () => ({getCapabilities: callback => callback({ok: true, data: {supported: true}})})};
  try {
    assert.deepEqual(await api.getCapabilities(), {supported: true});
  } finally {
    delete globalThis.uni;
  }
});

test('both UNI products ship the canonical JS bridge unchanged', async () => {
  assert.equal(await readFile(new URL('uni_modules/Sandrox-Sfiora/js_sdk/bridge.js', root), 'utf8'), bridge);
});

test('UTS JS waitForIdle consumes native state without inventing a bridge method', async () => {
  let writingQueries = 0;
  const api = await loadTransport('uni_modules/Sandrox-Sfiora/js_sdk/index.js', (method, _json, callback) => {
    assert.ok(['isScanning', 'isWriting'].includes(method));
    callback(JSON.stringify({ok: true, data: method === 'isWriting' && ++writingQueries < 2}));
  });
  await api.waitForIdle({timeoutMilliseconds: 500});
  assert.equal(writingQueries, 2);
});

const formatFixtures = JSON.parse(await readFile(new URL('tests/fixtures/bridge-contract.json', root), 'utf8'));
for (const transport of ['nativeplugin', 'uts']) {
  test(`${transport} formatting preserves results, options and errors without settling early`, async () => {
    const calls = [];
    let complete;
    const dispatch = (options, callback) => { calls.push(options); complete = callback; };
    const api = transport === 'nativeplugin'
      ? await loadTransport('adapters/uniapp/index.js')
      : await loadTransport('uni_modules/Sandrox-Sfiora/js_sdk/index.js', (method, json, callback) => {
          assert.equal(method, 'formatNdef');
          dispatch(JSON.parse(json)[0], envelope => callback(JSON.stringify(envelope)));
        });
    if (transport === 'nativeplugin') globalThis.uni = {requireNativePlugin: () => ({formatNdef: dispatch})};
    try {
      let settled = false;
      const pending = api.formatNdef().then(value => { settled = true; return value; });
      await Promise.resolve();
      assert.equal(settled, false);
      assert.deepEqual(calls[0], {});
      complete({ok: true, data: formatFixtures.bridgeFormatResult});
      complete({ok: false, error: {code: 'USER_CANCELLED'}});
      assert.deepEqual(await pending, formatFixtures.bridgeFormatResult);
      for (const verified of [true, false]) {
        const request = api.formatNdef(formatFixtures.bridgeFormatOptions);
        const preserved = {...formatFixtures.bridgeFormatResult, action: 'preserved', verified};
        complete({ok: true, data: preserved});
        assert.deepEqual(await request, preserved);
        assert.deepEqual(calls.at(-1), formatFixtures.bridgeFormatOptions);
      }
      for (const invalid of [null, [], 'format', 1, true]) {
        await assert.rejects(api.formatNdef(invalid), {code: 'INVALID_OPTIONS'});
      }
      assert.equal(calls.length, 3);
      for (const code of ['NFC_UNSUPPORTED', 'USER_CANCELLED', 'WRITE_TIMEOUT', 'WRITE_VERIFICATION_FAILED']) {
        const request = api.formatNdef();
        const error = {code, message: 'Native formatting failed', recoverable: false};
        complete({ok: false, error});
        await assert.rejects(request, error);
      }
    } finally {
      if (transport === 'nativeplugin') delete globalThis.uni;
    }
  });
}
