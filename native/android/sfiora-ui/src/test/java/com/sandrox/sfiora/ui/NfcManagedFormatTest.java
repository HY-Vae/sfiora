package com.sandrox.sfiora.ui;

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
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import com.sandrox.sfiora.NfcClient;
import com.sandrox.sfiora.NfcError;
import com.sandrox.sfiora.NfcErrorCode;
import com.sandrox.sfiora.NfcFormatResult;
import com.sandrox.sfiora.NfcWriteConfiguration;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowBasicTagTechnology;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowNfcAdapter;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/** Exercises the public controller and real panels, substituting only platform tag I/O. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = {NfcManagedFormatTest.FormatTechnology.class, NfcManagedFormatTest.NdefTechnology.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class NfcManagedFormatTest {
    private ActivityController<Activity> host;
    private NfcWriteController writer;
    private ShadowNfcAdapter adapter;
    private int verificationRequests;
    private final List<NfcFormatResult> results = new ArrayList<>();
    private final List<NfcError> failures = new ArrayList<>();
    private final List<Boolean> states = new ArrayList<>();
    private final NfcClient.FormatCallback callback = new NfcClient.FormatCallback() {
        @Override public void onVerificationRequired() { verificationRequests++; }
        @Override public void onSuccess(NfcFormatResult result) { results.add(result); }
        @Override public void onFailure(NfcError error) { failures.add(error); }
        @Override public void onStateChanged(boolean active) { states.add(active); }
    };

    @Before public void setUp() {
        FormatTechnology.calls = new AtomicInteger();
        NdefTechnology.live = emptyMessage();
        NdefTechnology.writable = true;
        NdefTechnology.readFailure = false;
        host = Robolectric.buildActivity(Activity.class).setup();
        shadowOf(host.get().getPackageManager()).setSystemFeature(PackageManager.FEATURE_NFC, true);
        adapter = shadowOf(NfcAdapter.getDefaultAdapter(host.get()));
        adapter.setEnabled(true);
        writer = new NfcWriteController(host.get());
    }

    @After public void tearDown() throws Exception {
        writer.close();
        await(() -> !writer.isWriting());
        assertTrue(activePanels().isEmpty());
        host.pause().stop().destroy();
    }

    @Test public void panelRequestsRediscoveryBeforeShowingVerifiedSuccess() throws Exception {
        begin();
        Dialog panel = panel();
        adapter.dispatchTagDiscovered(tag(1, 7));
        await(() -> verificationRequests == 1);
        assertSame(panel, panel());
        assertText(R.string.nfc_reader_format_verify);
        assertEquals(1, activePanels().size());
        assertTrue(writer.isWriting());
        assertTrue(results.isEmpty());

        adapter.dispatchTagDiscovered(tag(1, 6));
        await(() -> !results.isEmpty());
        assertTrue(failures.isEmpty());
        assertTrue(results.get(0).didFormat());
        assertTrue(results.get(0).isVerified());
        assertText(R.string.nfc_reader_format_success);
        assertEquals(List.of(true, false), states);
        assertEquals(1, FormatTechnology.calls.get());
        assertTrue("The result must not wait for panel dismissal", panel.isShowing());
    }

    @Test public void existingReadOnlyNdefShowsPreservationWithoutFormatting() throws Exception {
        NdefTechnology.writable = false;
        NdefTechnology.live = new android.nfc.NdefMessage(new android.nfc.NdefRecord[] {
                android.nfc.NdefRecord.createTextRecord("en", "Keep this content")});
        begin();
        adapter.dispatchTagDiscovered(tag(1, 6, 7));
        await(() -> !results.isEmpty());
        assertFalse(results.get(0).didFormat());
        assertTrue(results.get(0).isVerified());
        assertTrue(results.get(0).toJsonString().contains("Keep this content"));
        assertText(R.string.nfc_reader_format_preserved);
        assertEquals(0, FormatTechnology.calls.get());
        assertEquals(0, verificationRequests);
    }

    @Test public void unconfirmedContentUsesNoticeWithoutASuccessCheckmark() throws Exception {
        NdefTechnology.live = null;
        begin();
        adapter.dispatchTagDiscovered(tag(1, 6));
        await(() -> !results.isEmpty());
        assertFalse(results.get(0).isVerified());
        assertText(R.string.nfc_reader_format_unverified);
        assertEquals(View.INVISIBLE, panel().findViewById(R.id.nfc_reader_scan_animation).getVisibility());
        assertEquals(View.VISIBLE, panel().findViewById(R.id.nfc_reader_scan_status_icon).getVisibility());
        assertEquals(host.get().getString(R.string.nfc_reader_done),
                panel().findViewById(R.id.nfc_reader_scan_close).getContentDescription());
        AtomicInteger ended = new AtomicInteger();
        NfcPresentationState.waitForEnd(5000, new NfcPresentationState.Completion() {
            @Override public void onSuccess() { ended.incrementAndGet(); }
            @Override public void onFailure(NfcError error) { fail(error.getMessage()); }
        });
        assertEquals(0, ended.get());
        panel().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
        assertEquals(1, ended.get());
        assertTrue(failures.isEmpty());
        assertEquals(0, FormatTechnology.calls.get());
    }

    @Test @Config(qualifiers = "en-rUS-w360dp-h800dp-mdpi")
    public void verificationMismatchShowsFailureAndPreservesTheCoreError() throws Exception {
        begin();
        adapter.dispatchTagDiscovered(tag(1, 7));
        await(() -> verificationRequests == 1);
        adapter.dispatchTagDiscovered(tag(2, 6));
        await(() -> !failures.isEmpty());
        assertEquals(NfcErrorCode.WRITE_VERIFICATION_FAILED, failures.get(0).getCode());
        assertTrue(failures.get(0).getMessage().contains("may have changed"));
        assertText(R.string.nfc_reader_format_verification_failed);
        View decor = panel().getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 360, 800);
        TextView title = panel().findViewById(R.id.nfc_reader_scan_title);
        assertTrue("The full error title must fit, including when it wraps",
                title.getHeight() >= title.getLayout().getHeight());
        assertTrue(results.isEmpty());
        assertEquals(1, FormatTechnology.calls.get());
    }

    @Test public void failedPreservationReadShowsErrorWithoutFormatting() throws Exception {
        NdefTechnology.readFailure = true;
        begin();
        adapter.dispatchTagDiscovered(tag(1, 6, 7));
        await(() -> !failures.isEmpty());
        assertEquals(NfcErrorCode.READ_FAILED, failures.get(0).getCode());
        assertText(R.string.nfc_reader_format_failed_detail);
        assertTrue(results.isEmpty());
        assertEquals(0, FormatTechnology.calls.get());
    }

    @Test public void unsupportedTagHasFormatSpecificFeedback() throws Exception {
        begin();
        adapter.dispatchTagDiscovered(tag(1));
        await(() -> !failures.isEmpty());
        assertEquals(NfcErrorCode.UNSUPPORTED_TAG, failures.get(0).getCode());
        assertText(R.string.nfc_reader_format_unsupported);
        assertEquals(0, FormatTechnology.calls.get());
    }

    @Test public void cancellingRediscoveryDoesNotClaimRollbackOrSuccess() throws Exception {
        begin();
        adapter.dispatchTagDiscovered(tag(1, 7));
        await(() -> verificationRequests == 1);
        panel().findViewById(R.id.nfc_reader_scan_cancel).performClick();
        await(() -> !failures.isEmpty());
        assertEquals(NfcErrorCode.USER_CANCELLED, failures.get(0).getCode());
        assertTrue(failures.get(0).getMessage().contains("may have changed"));
        assertTrue(results.isEmpty());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
        assertTrue(activePanels().isEmpty());
    }

    @Test public void timeoutUsesOriginalDeadlineDuringRediscoveryAndShowsNoSuccess() throws Exception {
        writer.startFormat(NfcWriteConfiguration.builder().timeoutMillis(1000).build(), callback);
        adapter.dispatchTagDiscovered(tag(1, 7));
        await(() -> verificationRequests == 1);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        await(() -> !failures.isEmpty());
        assertEquals(NfcErrorCode.WRITE_TIMEOUT, failures.get(0).getCode());
        assertText(R.string.nfc_reader_format_timeout);
        assertTrue(results.isEmpty());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4));
        assertTrue(activePanels().isEmpty());
    }

    @Test public void busyStartKeepsTheActivePanelAndStopIsSilent() throws Exception {
        begin();
        Dialog first = panel();
        writer.startFormat(callback);
        assertEquals(1, failures.size());
        assertEquals(NfcErrorCode.WRITE_BUSY, failures.get(0).getCode());
        assertSame(first, panel());
        assertTrue(first.isShowing());
        writer.stopWrite();
        assertFalse(first.isShowing());
        await(() -> !writer.isWriting());
        assertEquals(1, failures.size());
        assertTrue(results.isEmpty());
    }

    @Test public void closedControllerRejectsFormattingWithoutAPanel() {
        writer.close();
        writer.startFormat(callback);
        assertEquals(NfcErrorCode.INTERNAL_ERROR, failures.get(0).getCode());
        assertTrue(activePanels().isEmpty());
    }

    @Test @Config(qualifiers = "zh-rCN")
    public void builtInMessagesFollowTheHostLocale() throws Exception {
        begin();
        assertEquals("已准备好扫描", ((TextView) panel().findViewById(R.id.nfc_reader_scan_title)).getText());
        adapter.dispatchTagDiscovered(tag(1, 7));
        await(() -> verificationRequests == 1);
        assertEquals("请移开标签，再将同一枚标签贴近手机，验证格式化结果。", message());
    }

    @Test public void completeMessageOverrideCoversPendingRediscoveryAndTerminalStates() throws Exception {
        NfcPresentationMessages.Format messages = new NfcPresentationMessages.Format(
                "title", "instruction", "cancel", "done", "success", "verify", "preserved",
                "unverified", "failure", "timeout", "disabled", "unavailable", "lost",
                "unsupported", "failed", "verification failed");
        writer.startFormat(null, messages, callback);
        assertEquals("instruction", message());
        adapter.dispatchTagDiscovered(tag(1, 7));
        await(() -> verificationRequests == 1);
        assertEquals("verify", message());
        adapter.dispatchTagDiscovered(tag(1, 6));
        await(() -> !results.isEmpty());
        assertEquals("success", message());
        assertEquals("done", ((TextView) panel().findViewById(R.id.nfc_reader_scan_cancel)).getText());
    }

    private void begin() { writer.startFormat(callback); }
    private Dialog panel() { return ShadowDialog.getLatestDialog(); }
    private String message() {
        return ((TextView) panel().findViewById(R.id.nfc_reader_scan_message)).getText().toString();
    }
    private void assertText(int resource) { assertEquals(host.get().getString(resource), message()); }
    private List<?> activePanels() {
        return (List<?>) NfcPresentationState.getState().get("activePresentationIds");
    }

    private static Tag tag(int id, int... technologies) {
        Bundle[] extras = new Bundle[technologies.length];
        for (int index = 0; index < extras.length; index++) extras[index] = new Bundle();
        return ReflectionHelpers.callStaticMethod(Tag.class, "createMockTag",
                ClassParameter.from(byte[].class, new byte[] {(byte) id}),
                ClassParameter.from(int[].class, technologies), ClassParameter.from(Bundle[].class, extras));
    }

    private static android.nfc.NdefMessage emptyMessage() {
        return new android.nfc.NdefMessage(new android.nfc.NdefRecord[] {
                new android.nfc.NdefRecord(android.nfc.NdefRecord.TNF_EMPTY, new byte[0], new byte[0], new byte[0])});
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(5);
        }
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue("NFC operation did not reach the expected state", condition.getAsBoolean());
    }

    @Implements(NdefFormatable.class)
    public static class FormatTechnology extends ShadowBasicTagTechnology {
        static AtomicInteger calls;
        @Implementation protected void __constructor__(Tag tag) { }
        @Implementation protected static NdefFormatable get(Tag tag) {
            return List.of(tag.getTechList()).contains(NdefFormatable.class.getName())
                    ? ReflectionHelpers.callConstructor(NdefFormatable.class, ClassParameter.from(Tag.class, tag)) : null;
        }
        @Implementation protected void connect() { }
        @Implementation protected void close() { }
        @Implementation protected void format(android.nfc.NdefMessage message) {
            assertArrayEquals(emptyMessage().toByteArray(), message.toByteArray());
            calls.incrementAndGet();
        }
    }

    @Implements(Ndef.class)
    public static class NdefTechnology extends ShadowBasicTagTechnology {
        static volatile android.nfc.NdefMessage live;
        static volatile boolean writable, readFailure;
        @Implementation protected void __constructor__(Tag tag) { }
        @Implementation protected static Ndef get(Tag tag) {
            return List.of(tag.getTechList()).contains(Ndef.class.getName())
                    ? ReflectionHelpers.callConstructor(Ndef.class, ClassParameter.from(Tag.class, tag)) : null;
        }
        @Implementation protected void connect() { }
        @Implementation protected void close() { }
        @Implementation protected boolean isWritable() { return writable; }
        @Implementation protected boolean canMakeReadOnly() { return true; }
        @Implementation protected int getMaxSize() { return 716; }
        @Implementation protected String getType() { return Ndef.MIFARE_CLASSIC; }
        @Implementation protected android.nfc.NdefMessage getNdefMessage() throws IOException {
            if (readFailure) throw new IOException("Read unavailable");
            return live;
        }
        @Implementation protected void writeNdefMessage(android.nfc.NdefMessage message) {
            throw new AssertionError("Formatting must preserve existing NDEF content");
        }
    }
}
