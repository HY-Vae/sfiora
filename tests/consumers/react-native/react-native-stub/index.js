'use strict';

const owners = new Set();
const foreground = () => ({platform: 'android', revision: 0, state: owners.size ? 'active' : 'disabled', error: null});
const nativeModule = {
  async acquireForegroundDispatch(ownerId) { owners.add(ownerId); return foreground(); },
  async releaseForegroundDispatch(ownerId) { owners.delete(ownerId); return foreground(); },
  async getForegroundDispatchState() { return foreground(); },
  async getPresentationState() { return {platform: 'android', supported: true, activePresentationIds: []}; },
  async waitForPresentationEnd() {},
  async getCapabilities() {
    return {
      platform: 'android',
      supported: true,
      enabled: true,
      readerModeSupported: true,
      features: ['ndefFormat'],
    };
  },
  async startScan() {
    return {
      schemaVersion: 1,
      platform: 'android',
    };
  },
  async cancelScan() {},
  async isScanning() {
    return false;
  },
  async writeNdef() {
    return {
      schemaVersion: 1,
      platform: 'android',
      operation: 'writeNdef',
      verified: true,
    };
  },
  async initializeNdef() {
    return {
      schemaVersion: 1,
      platform: 'android',
      operation: 'initializeNdef',
      action: 'preserved',
    };
  },
  async formatNdef() {
    return {
      schemaVersion: 1,
      platform: 'android',
      operation: 'formatNdef',
      action: 'preserved',
      verified: false,
    };
  },
  async cancelWrite() {},
  async isWriting() {
    return false;
  },
};

module.exports = {
  NativeModules: {
    Sfiora: nativeModule,
  },
  TurboModuleRegistry: {
    get(name) {
      return name === 'Sfiora' ? nativeModule : null;
    },
  },
};
