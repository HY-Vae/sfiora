package com.sandrox.sfiora.bridge;

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
import com.sandrox.sfiora.ui.NfcPresentationState;
import com.sandrox.sfiora.ui.R;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.json.JSONException;
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

/** Shared UNI runtime and managed panels with platform tag I/O substituted. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = {SfioraBridgeFormatTest.FormatTechnology.class,
        SfioraBridgeFormatTest.NdefTechnology.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class SfioraBridgeFormatTest {
    private Activity activity;
    private SfioraBridgeRuntime runtime;
    private ShadowNfcAdapter adapter;
    private final List<Reply> replies = new ArrayList<>();

    @Before public void setUp() {
        FormatTechnology.calls = new AtomicInteger();
        NdefTechnology.live = emptyMessage();
        NdefTechnology.writable = true;
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        shadowOf(activity.getPackageManager()).setSystemFeature(PackageManager.FEATURE_NFC, true);
        adapter = shadowOf(NfcAdapter.getDefaultAdapter(activity));
        adapter.setEnabled(true);
        runtime = new SfioraBridgeRuntime();
        runtime.onResume(activity);
    }

    @After public void tearDown() throws Exception {
        runtime.close();
        await(() -> !isWriting());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertTrue(((List<?>) NfcPresentationState.getState().get("activePresentationIds")).isEmpty());
        for (Reply reply : replies) assertFalse("Callback must run on main", reply.offMain);
    }

    @Test public void formatCallRemainsBusyUntilRediscoveryAndReturnsVerifiedResult() throws Exception {
        Reply format = start();
        firstPresentation();
        assertEquals(0, format.calls);
        assertTrue(isWriting());
        Reply second = reply();
        invoke("formatNdef", "[{}]", second);
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
        JSONObject ndef = format.payload.getJSONObject("tag").getJSONObject("ndef");
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
        JSONObject ndef = format.payload.getJSONObject("tag").getJSONObject("ndef");
        assertEquals(false, ndef.get("writable"));
        assertEquals("Keep this", ndef.getJSONArray("records").getJSONObject(0).get("text"));
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
        invoke("cancelWrite", "[]", reply());
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
        runtime.onPause();
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
        invoke("formatNdef", "[{\"timeoutMilliseconds\":30000}]", format);
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
        invoke("formatNdef", "[{\"force\":true}]", invalid);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("INVALID_OPTIONS", invalid.code);
        assertFalse(adapter.isInReaderMode());
        assertTrue(((List<?>) NfcPresentationState.getState().get("activePresentationIds")).isEmpty());
        runtime.onPause();
        Reply paused = reply();
        invoke("formatNdef", "[{}]", paused);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("INTERNAL_ERROR", paused.code);
        assertFalse(adapter.isInReaderMode());
    }

    @Test public void nativepluginMapTransportReturnsTheSamePreservedResult() throws Exception {
        Reply format = reply();
        runtime.invoke(activity, "formatNdef", new Object[] {null},
                envelope -> format.receive(new JSONObject(envelope).toString()));
        assertTrue(isWriting());
        adapter.dispatchTagDiscovered(tag(1, 6));
        await(() -> format.calls == 1);
        assertNull(format.code);
        assertEquals("preserved", format.payload.getString("action"));
        assertTrue(format.payload.getBoolean("verified"));
        assertEquals(0, FormatTechnology.calls.get());
    }

    private Reply start() {
        Reply reply = reply();
        invoke("formatNdef", "[{}]", reply);
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
                    .equals(activity.getString(R.string.nfc_reader_format_verify));
        });
    }

    private boolean isWriting() {
        Reply state = new Reply();
        invoke("isWriting", "[]", state);
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

    private void invoke(String method, String arguments, Reply reply) {
        runtime.invokeJson(activity, method, arguments, reply::receive);
    }

    private static final class Reply {
        int calls;
        Object value;
        String code;
        JSONObject payload;
        boolean offMain;

        void receive(String json) {
            calls++;
            offMain |= Looper.myLooper() != Looper.getMainLooper();
            try {
                JSONObject envelope = new JSONObject(json);
                if (envelope.getBoolean("ok")) {
                    value = envelope.get("data");
                    payload = envelope.optJSONObject("data");
                } else {
                    payload = envelope.getJSONObject("error");
                    code = payload.getString("code");
                }
            } catch (JSONException error) {
                throw new AssertionError("Invalid bridge result", error);
            }
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
