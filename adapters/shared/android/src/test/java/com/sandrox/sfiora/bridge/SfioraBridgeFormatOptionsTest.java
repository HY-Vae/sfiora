package com.sandrox.sfiora.bridge;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class SfioraBridgeFormatOptionsTest {
    @Test public void omittedOptionsUseNativeDefaults() throws Exception {
        for (Object options : new Object[] {null, Collections.emptyMap()}) {
            SfioraBridgeFormatOptions parsed = SfioraBridgeFormatOptions.parse(options);
            assertEquals(30_000L, parsed.getWriteConfiguration().getTimeoutMillis());
            assertNull(parsed.getMessages());
        }
    }

    @Test public void timeoutAcceptsBridgeNumbersWithinTheNativeRange() throws Exception {
        for (Number timeout : new Number[] {1000, 30_000L, 60_000.0}) {
            SfioraBridgeFormatOptions parsed = SfioraBridgeFormatOptions.parse(
                    Map.of("timeoutMilliseconds", timeout));
            assertEquals(timeout.longValue(), parsed.getWriteConfiguration().getTimeoutMillis());
        }
    }

    @Test public void invalidTimeoutsAreRejectedWithoutCoercion() {
        for (Object timeout : new Object[] {null, "60000", true, 999, 60_001, 1000.5,
                Double.NaN, Double.POSITIVE_INFINITY, Long.MAX_VALUE}) {
            assertThrows(SfioraBridgeOptionsException.class, () -> SfioraBridgeFormatOptions.parse(
                    Collections.singletonMap("timeoutMilliseconds", timeout)));
        }
    }

    @Test public void formatDoesNotAcceptWriteMessagesOrScanOptions() {
        for (Object options : new Object[] {"format", Collections.emptyList(),
                Map.of("records", Collections.emptyList()), Map.of("android", Map.of("presentation", "none")),
                Map.of("force", true), Collections.singletonMap(1, true)}) {
            assertThrows(SfioraBridgeOptionsException.class, () -> SfioraBridgeFormatOptions.parse(options));
        }
    }

    @Test public void completeFormattingCopyPreservesEveryField() throws Exception {
        Map<String, Object> messages = messages();
        SfioraBridgeFormatOptions parsed = SfioraBridgeFormatOptions.parse(Map.of("messages", messages));
        for (Map.Entry<String, Object> message : messages.entrySet()) {
            assertEquals(message.getValue(), parsed.getMessages().getClass()
                    .getField(message.getKey()).get(parsed.getMessages()));
        }
    }

    @Test public void partialBlankUnknownOrNonObjectCopyIsRejected() {
        for (Object value : new Object[] {null, "format", Collections.emptyList(), Collections.emptyMap()}) {
            assertThrows(SfioraBridgeOptionsException.class, () -> SfioraBridgeFormatOptions.parse(
                    Collections.singletonMap("messages", value)));
        }
        for (String key : messages().keySet()) {
            Map<String, Object> missing = messages();
            missing.remove(key);
            assertThrows(SfioraBridgeOptionsException.class,
                    () -> SfioraBridgeFormatOptions.parse(Map.of("messages", missing)));
            for (Object invalid : new Object[] {null, "", " \n ", 7, true}) {
                Map<String, Object> blank = messages();
                blank.put(key, invalid);
                assertThrows(SfioraBridgeOptionsException.class,
                        () -> SfioraBridgeFormatOptions.parse(Map.of("messages", blank)));
            }
        }
        Map<String, Object> extra = messages();
        extra.put("writeFailed", "Write failed");
        assertThrows(SfioraBridgeOptionsException.class,
                () -> SfioraBridgeFormatOptions.parse(Map.of("messages", extra)));
    }

    private static Map<String, Object> messages() {
        Map<String, Object> messages = new LinkedHashMap<>();
        for (String key : new String[] {"title", "instruction", "cancel", "done", "success",
                "verificationRequired", "preserved", "preservedUnverified", "failureTitle", "timeout",
                "nfcDisabled", "nfcUnsupported", "tagLost", "unsupportedTag", "formatFailed",
                "verificationFailed"}) {
            messages.put(key, "  Custom " + key + "  ");
        }
        return messages;
    }
}
