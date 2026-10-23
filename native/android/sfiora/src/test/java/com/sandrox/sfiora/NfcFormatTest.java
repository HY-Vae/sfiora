package com.sandrox.sfiora;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.TagLostException;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.Bundle;
import android.os.Looper;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
import org.robolectric.shadows.ShadowBasicTagTechnology;
import org.robolectric.shadows.ShadowNfcAdapter;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/** Production client with only platform NFC I/O replaced. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = {NfcFormatTest.FormatTechnology.class, NfcFormatTest.NdefTechnology.class})
public class NfcFormatTest {
    private Activity activity;
    private NfcClient client;
    private ShadowNfcAdapter adapter;
    private int verificationRequests;
    private boolean rejectVerificationRequest;
    private final List<NfcError> failures = new ArrayList<>();
    private final List<NfcFormatResult> results = new ArrayList<>();
    private final List<Boolean> states = new ArrayList<>();
    private final NfcClient.FormatCallback callback = new NfcClient.FormatCallback() {
        @Override public void onVerificationRequired() {
            assertEquals(Looper.getMainLooper(), Looper.myLooper());
            verificationRequests++;
            if (rejectVerificationRequest) {
                throw new IllegalStateException("The host cannot show verification instructions");
            }
        }
        @Override public void onSuccess(NfcFormatResult result) {
            assertEquals(Looper.getMainLooper(), Looper.myLooper());
            results.add(result);
        }
        @Override public void onFailure(NfcError error) {
            assertEquals(Looper.getMainLooper(), Looper.myLooper());
            failures.add(error);
        }
        @Override public void onStateChanged(boolean active) { states.add(active); }
    };

    @Before public void setUp() {
        FormatTechnology.count = new AtomicInteger();
        FormatTechnology.closes = new AtomicInteger();
        FormatTechnology.entered = new CountDownLatch(1);
        FormatTechnology.gate = new CountDownLatch(0);
        FormatTechnology.failure = null;
        FormatTechnology.closeFailure = null;
        FormatTechnology.closeOnMain = false;
        NdefTechnology.live = emptyMessage();
        NdefTechnology.writable = true;
        NdefTechnology.readFailure = false;
        NdefTechnology.reads = new AtomicInteger();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        shadowOf(activity.getPackageManager()).setSystemFeature(PackageManager.FEATURE_NFC, true);
        adapter = shadowOf(NfcAdapter.getDefaultAdapter(activity));
        adapter.setEnabled(true);
        client = new NfcClient(activity);
    }

    @After public void tearDown() throws Exception {
        FormatTechnology.gate.countDown();
        client.close();
        await(() -> !client.isWriting() && !client.isReading());
    }

    @Test public void successRequiresRediscoverySameIdWritableNdefAndExactLiveEmptyMessage() throws Exception {
        begin();
        Tag first = tag(1, 7);
        adapter.dispatchTagDiscovered(first);
        await(() -> verificationRequests == 1);
        assertTrue(results.isEmpty());
        assertTrue(client.isWriting());
        assertEquals(1, FormatTechnology.count.get());
        assertTrue(FormatTechnology.closes.get() > 0);
        assertFalse(FormatTechnology.closeOnMain);
        adapter.dispatchTagDiscovered(first); // A duplicate delivery must never format again.
        adapter.dispatchTagDiscovered(tag(1, 6, 7));
        await(() -> !client.isWriting());
        assertTrue(failures.isEmpty());
        assertEquals(1, results.size());
        assertTrue(results.get(0).didFormat());
        assertTrue(results.get(0).isVerified());
        assertEquals("formatNdef", results.get(0).toMap().get("operation"));
        assertEquals("formatted", results.get(0).toMap().get("action"));
        assertEquals(1, FormatTechnology.count.get());
        assertEquals(1, NdefTechnology.reads.get());
        assertEquals(List.of(true, false), states);
    }

    @Test public void existingNdefIncludingReadOnlyContentIsPreservedWithoutFormatOrWrite() throws Exception {
        NdefTechnology.writable = false;
        NdefTechnology.live = new android.nfc.NdefMessage(new android.nfc.NdefRecord[] {
                android.nfc.NdefRecord.createTextRecord("en", "existing content")});
        begin();
        adapter.dispatchTagDiscovered(tag(1, 6));
        await(() -> !client.isWriting());
        assertTrue(failures.isEmpty());
        assertEquals("preserved", results.get(0).toMap().get("action"));
        assertFalse(results.get(0).didFormat());
        assertTrue(results.get(0).toJsonString().contains("existing content"));
        assertEquals(0, FormatTechnology.count.get());
        assertEquals(0, verificationRequests);
    }

    @Test public void unsupportedTagIsRejectedBeforeAnyWrite() throws Exception {
        begin();
        adapter.dispatchTagDiscovered(tag(1));
        await(() -> !client.isWriting());
        assertEquals(NfcErrorCode.UNSUPPORTED_TAG, failures.get(0).getCode());
        assertEquals(0, FormatTechnology.count.get());
    }

    @Test public void existingNdefReadFailureNeverFallsBackToFormatting() throws Exception {
        NdefTechnology.readFailure = true;
        begin();
        adapter.dispatchTagDiscovered(tag(1, 6, 7));
        await(() -> !client.isWriting());
        assertTrue(results.isEmpty());
        assertEquals(NfcErrorCode.READ_FAILED, failures.get(0).getCode());
        assertFalse(failures.get(0).getMessage().contains("may have changed"));
        assertEquals(0, FormatTechnology.count.get());
        assertEquals(0, verificationRequests);
    }

    @Test public void nullLiveMessageOnExistingNdefDoesNotClaimVerifiedPreservationOrFormat() throws Exception {
        NdefTechnology.live = null;
        begin();
        adapter.dispatchTagDiscovered(tag(1, 6));
        await(() -> !client.isWriting());
        assertTrue(failures.isEmpty());
        assertEquals(1, results.size());
        assertFalse(results.get(0).didFormat());
        assertFalse(results.get(0).isVerified());
        assertEquals("preserved", results.get(0).toMap().get("action"));
        assertEquals(Boolean.FALSE, results.get(0).toMap().get("verified"));
        assertTrue(results.get(0).toJsonString().contains("readError"));
        assertTrue(results.get(0).toJsonString().contains("content is unverified"));
        assertEquals(0, FormatTechnology.count.get());
    }

    @Test public void nullLiveMessageAfterFormatDoesNotCountAsTheExpectedEmptyRecord() throws Exception {
        formatFirst();
        NdefTechnology.live = null;
        adapter.dispatchTagDiscovered(tag(1, 6));
        assertVerificationFailure();
    }

    @Test public void missingIdIsRejectedBeforeAnyWrite() throws Exception {
        begin();
        Tag withoutId = ReflectionHelpers.callStaticMethod(Tag.class, "createMockTag",
                ClassParameter.from(byte[].class, new byte[0]),
                ClassParameter.from(int[].class, new int[] {7}),
                ClassParameter.from(Bundle[].class, new Bundle[] {new Bundle()}));
        adapter.dispatchTagDiscovered(withoutId);
        await(() -> !client.isWriting());
        assertEquals(NfcErrorCode.UNSUPPORTED_TAG, failures.get(0).getCode());
        assertEquals(0, FormatTechnology.count.get());
    }

    @Test public void differentTagCannotVerifyOrTriggerAnotherFormat() throws Exception {
        formatFirst();
        adapter.dispatchTagDiscovered(tag(2, 6));
        assertVerificationFailure();
        assertEquals(0, NdefTechnology.reads.get());
    }

    @Test public void stillUnformattedTagFailsWithoutAutomaticRetry() throws Exception {
        formatFirst();
        adapter.dispatchTagDiscovered(tag(1, 7));
        assertVerificationFailure();
    }

    @Test public void wrongMessageCannotPassVerification() throws Exception {
        formatFirst();
        NdefTechnology.live = new android.nfc.NdefMessage(new android.nfc.NdefRecord[] {
                android.nfc.NdefRecord.createTextRecord("en", "wrong")});
        adapter.dispatchTagDiscovered(tag(1, 6));
        assertVerificationFailure();
    }

    @Test public void readOnlyAfterFormatCannotPassVerification() throws Exception {
        formatFirst();
        NdefTechnology.writable = false;
        adapter.dispatchTagDiscovered(tag(1, 6));
        assertVerificationFailure();
    }

    @Test public void lostTagDuringVerificationDoesNotUseCachedMessage() throws Exception {
        formatFirst();
        NdefTechnology.readFailure = true;
        adapter.dispatchTagDiscovered(tag(1, 6));
        assertVerificationFailure();
    }

    @Test public void formatFailureReportsUncertainStateAndNeverRetries() throws Exception {
        FormatTechnology.failure = new IOException("partial format");
        begin();
        adapter.dispatchTagDiscovered(tag(1, 7));
        await(() -> !client.isWriting());
        assertEquals(NfcErrorCode.WRITE_FAILED, failures.get(0).getCode());
        assertUncertain();
        assertEquals(0, verificationRequests);
        assertEquals(1, FormatTechnology.count.get());
    }

    @Test public void removingTagDuringFormatReportsTagLostAndUncertainState() throws Exception {
        FormatTechnology.failure = new TagLostException("removed");
        begin();
        adapter.dispatchTagDiscovered(tag(1, 7));
        await(() -> !client.isWriting());
        assertEquals(NfcErrorCode.TAG_LOST, failures.get(0).getCode());
        assertUncertain();
    }

    @Test public void formatCloseFailureDoesNotStartVerificationOrRepeatWrite() throws Exception {
        FormatTechnology.closeFailure = new SecurityException("stale tag cookie");
        begin();
        adapter.dispatchTagDiscovered(tag(1, 7));
        await(() -> !client.isWriting());
        assertEquals(NfcErrorCode.WRITE_FAILED, failures.get(0).getCode());
        assertUncertain();
        assertEquals(1, FormatTechnology.count.get());
        assertEquals(0, verificationRequests);
        assertFalse(FormatTechnology.closeOnMain);
    }

    @Test public void verificationPromptFailureEndsTheOperationWithoutClaimingSuccess() throws Exception {
        rejectVerificationRequest = true;
        begin();
        adapter.dispatchTagDiscovered(tag(1, 7));
        await(() -> !client.isWriting());
        assertEquals(NfcErrorCode.INTERNAL_ERROR, failures.get(0).getCode());
        assertUncertain();
        assertEquals(1, FormatTechnology.count.get());
        assertEquals(1, verificationRequests);
        assertFalse(adapter.isInReaderMode());
        assertEquals(List.of(true, false), states);
    }

    @Test public void normalReadNeverFormatsAnUnformattedTag() throws Exception {
        List<NfcTagSnapshot> reads = new ArrayList<>();
        client.startRead(NfcReadConfiguration.builder().build(), new NfcClient.ReadCallback() {
            @Override public void onSuccess(NfcTagSnapshot tag) { reads.add(tag); }
            @Override public void onFailure(NfcError error) { failures.add(error); }
        });
        adapter.dispatchTagDiscovered(tag(1, 7));
        await(() -> !client.isReading());
        assertTrue(failures.isEmpty());
        assertEquals(1, reads.size());
        assertTrue(reads.get(0).toJsonString().contains("unsupported"));
        assertEquals(0, FormatTechnology.count.get());
    }

    @Test public void normalWriteNeverFormatsAnUnformattedTag() throws Exception {
        client.startWrite(new NdefMessage(List.of(NdefRecord.text("test", "en"))),
                NfcWriteConfiguration.builder().build(), new NfcClient.WriteCallback() {
                    @Override public void onSuccess(NfcWriteResult result) { fail("unexpected write"); }
                    @Override public void onFailure(NfcError error) { failures.add(error); }
                });
        adapter.dispatchTagDiscovered(tag(1, 7));
        await(() -> !client.isWriting());
        assertEquals(NfcErrorCode.UNSUPPORTED_TAG, failures.get(0).getCode());
        assertEquals(0, FormatTechnology.count.get());
    }

    @Test public void markerInitializationNeverFormatsAnUnformattedTag() throws Exception {
        NdefExternalType marker = new NdefExternalType("example.com", "initialized");
        NdefMessage message = new NdefMessage(List.of(
                NdefRecord.external(marker.getDomain(), marker.getType(), new byte[] {1})));
        client.startInitialize(message, marker, NfcWriteConfiguration.builder().build(),
                new NfcClient.InitializationCallback() {
                    @Override public void onSuccess(NfcInitializationResult result) { fail("unexpected write"); }
                    @Override public void onFailure(NfcError error) { failures.add(error); }
                });
        adapter.dispatchTagDiscovered(tag(1, 7));
        await(() -> !client.isWriting());
        assertEquals(NfcErrorCode.UNSUPPORTED_TAG, failures.get(0).getCode());
        assertEquals(0, FormatTechnology.count.get());
    }

    @Test public void cancellationBeforeDiscoveryMakesNoChanges() throws Exception {
        begin();
        client.cancelWrite();
        await(() -> !client.isWriting());
        assertEquals(NfcErrorCode.USER_CANCELLED, failures.get(0).getCode());
        assertFalse(failures.get(0).getMessage().contains("may have changed"));
        assertEquals(0, FormatTechnology.count.get());
    }

    @Test public void cancellationWhileWaitingForVerificationDoesNotClaimSuccess() throws Exception {
        formatFirst();
        client.cancelWrite();
        await(() -> !client.isWriting());
        assertEquals(NfcErrorCode.USER_CANCELLED, failures.get(0).getCode());
        assertUncertain();
    }

    @Test public void newOperationDoesNotReuseCancelledVerificationOrAcceptItsCallback() throws Exception {
        formatFirst();
        NfcAdapter.ReaderCallback oldReader = ReflectionHelpers.getField(adapter, "readerCallback");
        client.cancelWrite();
        await(() -> !client.isWriting());
        assertUncertain();
        failures.clear();

        begin();
        oldReader.onTagDiscovered(tag(1, 6));
        // A different, already formatted tag is a fresh operation, not verification of the old tag.
        adapter.dispatchTagDiscovered(tag(2, 6));
        await(() -> !client.isWriting());
        assertTrue(failures.isEmpty());
        assertEquals(1, results.size());
        assertFalse(results.get(0).didFormat());
        assertTrue(results.get(0).isVerified());
        assertEquals(1, FormatTechnology.count.get());
        assertEquals(1, NdefTechnology.reads.get());
        assertEquals(1, verificationRequests);
    }

    @Test public void verificationUsesOriginalDeadlineAndReportsUncertainTimeout() throws Exception {
        formatFirst();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
        await(() -> !client.isWriting());
        assertEquals(NfcErrorCode.WRITE_TIMEOUT, failures.get(0).getCode());
        assertUncertain();
    }

    @Test public void formatRetainsProcessLeaseThroughVerification() throws Exception {
        formatFirst();
        try (NfcClient other = new NfcClient(activity)) {
            other.startFormat(NfcWriteConfiguration.builder().build(), callback);
            assertEquals(NfcErrorCode.WRITE_BUSY, failures.get(0).getCode());
        }
        assertEquals(1, FormatTechnology.count.get());
    }

    @Test public void stopSilencesPendingVerificationAndPreventsFurtherWrites() throws Exception {
        formatFirst();
        NfcAdapter.ReaderCallback oldReader = ReflectionHelpers.getField(adapter, "readerCallback");
        client.stop();
        await(() -> !client.isWriting());
        oldReader.onTagDiscovered(tag(1, 7));
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(failures.isEmpty());
        assertTrue(results.isEmpty());
        assertEquals(1, FormatTechnology.count.get());
    }

    @Test public void cancelDuringBlockedFormatClosesOffMainAndWaitsForWorkerExit() throws Exception {
        FormatTechnology.gate = new CountDownLatch(1);
        begin();
        adapter.dispatchTagDiscovered(tag(1, 7));
        assertTrue(FormatTechnology.entered.await(5, TimeUnit.SECONDS));
        client.cancelWrite();
        await(() -> FormatTechnology.closes.get() > 0);
        assertTrue(client.isWriting());
        assertFalse(FormatTechnology.closeOnMain);
        FormatTechnology.gate.countDown();
        await(() -> !client.isWriting());
        assertEquals(NfcErrorCode.USER_CANCELLED, failures.get(0).getCode());
        assertUncertain();
        assertEquals(0, verificationRequests);
    }

    private void begin() { client.startFormat(NfcWriteConfiguration.builder().build(), callback); }

    private void formatFirst() throws Exception {
        begin();
        adapter.dispatchTagDiscovered(tag(1, 7));
        await(() -> verificationRequests == 1);
    }

    private void assertVerificationFailure() throws Exception {
        await(() -> !client.isWriting());
        assertEquals(NfcErrorCode.WRITE_VERIFICATION_FAILED, failures.get(0).getCode());
        assertUncertain();
        assertEquals(1, FormatTechnology.count.get());
    }

    private void assertUncertain() {
        assertTrue(results.isEmpty());
        assertTrue(failures.get(0).getMessage().contains("may have changed"));
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
        assertTrue("client did not reach expected state", condition.getAsBoolean());
    }

    @Implements(NdefFormatable.class)
    public static class FormatTechnology extends ShadowBasicTagTechnology {
        static AtomicInteger count, closes;
        static CountDownLatch entered, gate;
        static volatile IOException failure;
        static volatile RuntimeException closeFailure;
        static volatile boolean closeOnMain;
        @Implementation protected void __constructor__(Tag tag) { }
        @Implementation protected static NdefFormatable get(Tag tag) {
            return List.of(tag.getTechList()).contains(NdefFormatable.class.getName())
                    ? ReflectionHelpers.callConstructor(NdefFormatable.class, ClassParameter.from(Tag.class, tag)) : null;
        }
        @Implementation protected void connect() { }
        @Implementation protected void format(android.nfc.NdefMessage message) throws IOException {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper());
            assertArrayEquals(emptyMessage().toByteArray(), message.toByteArray());
            count.incrementAndGet();
            entered.countDown();
            boolean interrupted = false;
            while (true) {
                try { gate.await(); break; }
                catch (InterruptedException error) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
            if (failure != null) throw failure;
        }
        @Implementation protected void close() {
            closeOnMain |= Looper.myLooper() == Looper.getMainLooper();
            closes.incrementAndGet();
            if (closeFailure != null) throw closeFailure;
        }
    }

    @Implements(Ndef.class)
    public static class NdefTechnology extends ShadowBasicTagTechnology {
        static volatile android.nfc.NdefMessage live;
        static volatile boolean writable, readFailure;
        static AtomicInteger reads;
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
            reads.incrementAndGet();
            if (readFailure) throw new TagLostException("removed during verification");
            return live;
        }
        @Implementation protected android.nfc.NdefMessage getCachedNdefMessage() {
            throw new AssertionError("Verification must read live data");
        }
        @Implementation protected void writeNdefMessage(android.nfc.NdefMessage message) {
            throw new AssertionError("Existing NDEF content must never be overwritten by format");
        }
    }
}
