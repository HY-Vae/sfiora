package com.sandrox.sfiora.reactnative;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Dialog;
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.Bundle;
import android.os.Looper;
import android.widget.TextView;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.BridgeReactContext;
import com.facebook.react.bridge.JavaOnlyMap;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.WritableNativeMap;
import com.sandrox.sfiora.ui.NfcPresentationState;
import com.sandrox.sfiora.ui.R;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowBasicTagTechnology;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowNfcAdapter;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/** Real RN delegate and managed panels with platform tag I/O substituted. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = {SfioraModuleFormatTest.MapTransport.class,
        SfioraModuleFormatTest.FormatTechnology.class, SfioraModuleFormatTest.NdefTechnology.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class SfioraModuleFormatTest {
    private ReactApplicationContext context;
    private SfioraModuleDelegate delegate;
    private ShadowNfcAdapter adapter;
    private final List<Reply> replies = new ArrayList<>();

    @Before public void setUp() {
        MapTransport.lastValue = null;
        FormatTechnology.calls = new AtomicInteger();
        NdefTechnology.live = emptyMessage();
        NdefTechnology.writable = true;
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        shadowOf(activity.getPackageManager()).setSystemFeature(PackageManager.FEATURE_NFC, true);
        adapter = shadowOf(NfcAdapter.getDefaultAdapter(activity));
        adapter.setEnabled(true);
        context = new BridgeReactContext(activity.getApplicationContext());
        context.onHostResume(activity);
        delegate = new SfioraModuleDelegate(context);
    }

    @After public void tearDown() throws Exception {
        delegate.invalidate();
        await(() -> !isWriting());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertTrue(((List<?>) NfcPresentationState.getState().get("activePresentationIds")).isEmpty());
        for (Reply reply : replies) assertFalse("Promise must settle on main", reply.offMain);
    }

    @Test public void formatPromiseRemainsBusyUntilRediscoveryAndReturnsVerifiedResult() throws Exception {
        Reply format = start();
        firstPresentation();
        assertEquals(0, format.calls);
        assertTrue(isWriting());
        Reply second = reply();
        delegate.formatNdef(null, second.promise);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("WRITE_BUSY", second.code);
        adapter.dispatchTagDiscovered(tag(1, 6));
        await(() -> format.calls == 1);
        assertNull(format.code);
        assertEquals("formatNdef", format.payload.get("operation"));
        assertEquals("formatted", format.payload.get("action"));
        assertEquals(true, format.payload.get("verified"));
        assertEquals(1, FormatTechnology.calls.get());
        assertFalse(isWriting());
        Map<?, ?> tag = (Map<?, ?>) format.payload.get("tag");
        Map<?, ?> ndef = (Map<?, ?>) tag.get("ndef");
        assertEquals(true, ndef.get("writable"));
        assertEquals("D8000000", ndef.get("messageHex"));
    }

    @Test public void existingReadOnlyNdefIsPreservedWithItsLiveContent() throws Exception {
        NdefTechnology.writable = false;
        NdefTechnology.live = new android.nfc.NdefMessage(android.nfc.NdefRecord.createTextRecord("en", "Keep this"));
        Reply format = start();
        adapter.dispatchTagDiscovered(tag(1, 6));
        await(() -> format.calls == 1);
        assertNull(format.code);
        assertEquals("preserved", format.payload.get("action"));
        assertEquals(true, format.payload.get("verified"));
        Map<?, ?> ndef = (Map<?, ?>) ((Map<?, ?>) format.payload.get("tag")).get("ndef");
        assertEquals(false, ndef.get("writable"));
        assertEquals("Keep this", ((Map<?, ?>) ((List<?>) ndef.get("records")).get(0)).get("text"));
        assertEquals(0, FormatTechnology.calls.get());
    }

    @Test public void unreadExistingContentDoesNotBecomeVerifiedOrGetFormatted() throws Exception {
        NdefTechnology.live = null;
        Reply format = start();
        adapter.dispatchTagDiscovered(tag(1, 6));
        await(() -> format.calls == 1);
        assertNull(format.code);
        assertEquals("preserved", format.payload.get("action"));
        assertEquals(false, format.payload.get("verified"));
        assertEquals(0, FormatTechnology.calls.get());
    }

    @Test public void cancelAfterFormattingRejectsOnceAndDoesNotAcceptALateTag() throws Exception {
        Reply format = start();
        firstPresentation();
        delegate.cancelWrite(reply().promise);
        await(() -> format.calls == 1 && !isWriting());
        assertEquals("USER_CANCELLED", format.code);
        assertTrue(((String) format.payload.get("message")).contains("may have changed"));
        adapter.dispatchTagDiscovered(tag(1, 6));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, format.calls);
        assertEquals(1, FormatTechnology.calls.get());
    }

    @Test public void pauseCancelsPendingVerificationAndReleasesThePanel() throws Exception {
        Reply format = start();
        firstPresentation();
        context.onHostPause();
        await(() -> format.calls == 1 && !isWriting());
        assertEquals("USER_CANCELLED", format.code);
        assertTrue(((List<?>) NfcPresentationState.getState().get("activePresentationIds")).isEmpty());
    }

    @Test public void wrongTagFailsVerificationInsteadOfResolvingSuccess() throws Exception {
        Reply format = start();
        firstPresentation();
        adapter.dispatchTagDiscovered(tag(2, 6));
        await(() -> format.calls == 1);
        assertEquals("WRITE_VERIFICATION_FAILED", format.code);
        assertTrue(((String) format.payload.get("message")).contains("may have changed"));
    }

    @Test public void timeoutCoversBothPresentations() throws Exception {
        Reply format = reply();
        delegate.formatNdef(JavaOnlyMap.of("timeoutMilliseconds", 30_000), format.promise);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(isWriting());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
        firstPresentation();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(25));
        await(() -> format.calls == 1);
        assertEquals("WRITE_TIMEOUT", format.code);
        assertEquals(1, FormatTechnology.calls.get());
    }

    @Test public void invalidOptionsAndPausedHostCannotStartFormatting() {
        Reply invalid = reply();
        delegate.formatNdef(JavaOnlyMap.of("force", true), invalid.promise);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("INVALID_OPTIONS", invalid.code);
        assertFalse(adapter.isInReaderMode());
        assertTrue(((List<?>) NfcPresentationState.getState().get("activePresentationIds")).isEmpty());
        context.onHostPause();
        Reply paused = reply();
        delegate.formatNdef(null, paused.promise);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("INTERNAL_ERROR", paused.code);
        assertFalse(adapter.isInReaderMode());
    }

    private Reply start() {
        Reply reply = reply();
        delegate.formatNdef(null, reply.promise);
        assertTrue(isWriting());
        return reply;
    }

    private void firstPresentation() throws Exception {
        shadowOf(Looper.getMainLooper()).idle();
        adapter.dispatchTagDiscovered(tag(1, 7));
        await(() -> {
            Dialog panel = ShadowDialog.getLatestDialog();
            if (panel == null) return false;
            TextView message = panel.findViewById(R.id.nfc_reader_scan_message);
            return message != null && message.getText().toString()
                    .equals(context.getString(R.string.nfc_reader_format_verify));
        });
    }

    private boolean isWriting() {
        Reply state = new Reply();
        delegate.isWriting(state.promise);
        shadowOf(Looper.getMainLooper()).idle();
        return Boolean.TRUE.equals(state.value);
    }

    private Reply reply() { Reply reply = new Reply(); replies.add(reply); return reply; }

    private static void await(BooleanSupplier predicate) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!predicate.getAsBoolean() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(5);
        }
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(predicate.getAsBoolean());
    }

    private static Tag tag(int id, int technology) {
        return ReflectionHelpers.callStaticMethod(Tag.class, "createMockTag",
                ClassParameter.from(byte[].class, new byte[] {(byte) id}),
                ClassParameter.from(int[].class, new int[] {1, technology}),
                ClassParameter.from(Bundle[].class, new Bundle[] {new Bundle(), new Bundle()}));
    }

    private static android.nfc.NdefMessage emptyMessage() {
        return new android.nfc.NdefMessage(new android.nfc.NdefRecord(
                android.nfc.NdefRecord.TNF_EMPTY, new byte[0], new byte[0], new byte[0]));
    }

    private static final class Reply {
        int calls;
        Object value;
        String code;
        Map<String, Object> payload;
        boolean offMain;
        final Promise promise = (Promise) Proxy.newProxyInstance(Promise.class.getClassLoader(),
                new Class<?>[] {Promise.class}, (proxy, method, args) -> {
                    if (method.getName().equals("resolve") || method.getName().equals("reject")) {
                        calls++;
                        offMain |= Looper.myLooper() != Looper.getMainLooper();
                        payload = MapTransport.lastValue;
                        if (method.getName().equals("resolve")) value = args[0];
                        else code = (String) args[0];
                    }
                    return null;
                });
    }

    @Implements(Arguments.class)
    public static class MapTransport {
        static Map<String, Object> lastValue;
        @Implementation protected static WritableNativeMap makeNativeMap(Map<String, Object> value) {
            lastValue = value;
            return null;
        }
    }

    @Implements(NdefFormatable.class)
    public static class FormatTechnology extends ShadowBasicTagTechnology {
        static AtomicInteger calls;
        @Implementation protected void __constructor__(Tag tag) {}
        @Implementation protected static NdefFormatable get(Tag tag) {
            return Arrays.asList(tag.getTechList()).contains(NdefFormatable.class.getName())
                    ? ReflectionHelpers.callConstructor(NdefFormatable.class, ClassParameter.from(Tag.class, tag)) : null;
        }
        @Implementation protected void connect() {}
        @Implementation protected void close() {}
        @Implementation protected void format(android.nfc.NdefMessage message) {
            assertArrayEquals(emptyMessage().toByteArray(), message.toByteArray());
            calls.incrementAndGet();
            NdefTechnology.live = message;
        }
    }

    @Implements(Ndef.class)
    public static class NdefTechnology extends ShadowBasicTagTechnology {
        static volatile android.nfc.NdefMessage live;
        static volatile boolean writable;
        @Implementation protected void __constructor__(Tag tag) {}
        @Implementation protected static Ndef get(Tag tag) {
            return Arrays.asList(tag.getTechList()).contains(Ndef.class.getName())
                    ? ReflectionHelpers.callConstructor(Ndef.class, ClassParameter.from(Tag.class, tag)) : null;
        }
        @Implementation protected void connect() {}
        @Implementation protected void close() {}
        @Implementation protected boolean isWritable() { return writable; }
        @Implementation protected boolean canMakeReadOnly() { return true; }
        @Implementation protected int getMaxSize() { return 144; }
        @Implementation protected String getType() { return Ndef.NFC_FORUM_TYPE_2; }
        @Implementation protected android.nfc.NdefMessage getNdefMessage() { return live; }
        @Implementation protected void writeNdefMessage(android.nfc.NdefMessage message) {
            fail("Formatting must not write an already NDEF tag");
        }
    }
}
