package com.sandrox.sfiora;

import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Verified explicit formatting, or an existing NDEF tag preserved without writes.
 * A formatted result requires rediscovery of the same tag ID and verification
 * of the writable, empty NDEF message. For preserved tags, check isVerified()
 * before treating the returned content as verified.
 */
public final class NfcFormatResult {
    private final boolean formatted;
    private final boolean verified;
    private final NfcTagSnapshot tag;
    private final long completedAtEpochMillis;

    NfcFormatResult(
            boolean formatted,
            boolean verified,
            NfcTagSnapshot tag,
            long completedAtEpochMillis
    ) {
        if (formatted && !verified) {
            throw new IllegalArgumentException("A formatted result requires verified NDEF content");
        }
        this.formatted = formatted;
        this.verified = verified;
        this.tag = Objects.requireNonNull(tag, "tag is required");
        this.completedAtEpochMillis = completedAtEpochMillis;
    }

    /** False means the tag already exposed NDEF and no format/write was issued. */
    public boolean didFormat() {
        return formatted;
    }

    public NfcTagSnapshot getTag() {
        return tag;
    }

    /**
     * Whether live NDEF content was obtained. A formatted result additionally
     * guarantees a matching tag ID, writable NDEF, and the expected empty
     * message. A preserved result does not guarantee writability; false means
     * Android returned no live message and the contents remain unverified.
     */
    public boolean isVerified() {
        return verified;
    }

    public long getCompletedAtEpochMillis() {
        return completedAtEpochMillis;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("platform", "android");
        result.put("operation", "formatNdef");
        result.put("action", formatted ? "formatted" : "preserved");
        result.put("verified", verified);
        result.put("completedAtEpochMs", completedAtEpochMillis);
        result.put("tag", tag.toMap());
        return Collections.unmodifiableMap(result);
    }

    public String toJsonString() {
        return new JSONObject(toMap()).toString();
    }

    public String toPrettyJsonString() {
        try {
            return new JSONObject(toMap()).toString(2);
        } catch (Exception ignored) {
            return toJsonString();
        }
    }
}
