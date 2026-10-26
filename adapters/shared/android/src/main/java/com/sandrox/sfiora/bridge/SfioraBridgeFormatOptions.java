package com.sandrox.sfiora.bridge;

import com.sandrox.sfiora.NfcWriteConfiguration;
import com.sandrox.sfiora.ui.NfcPresentationMessages;

import java.util.Collections;
import java.util.Map;

/** Validates formatting settings before opening a panel or NFC session. */
public final class SfioraBridgeFormatOptions {
    private final NfcWriteConfiguration configuration;
    private final NfcPresentationMessages.Format messages;

    private SfioraBridgeFormatOptions(
            NfcWriteConfiguration configuration,
            NfcPresentationMessages.Format messages
    ) {
        this.configuration = configuration;
        this.messages = messages;
    }

    public NfcWriteConfiguration getWriteConfiguration() {
        return configuration;
    }

    public NfcPresentationMessages.Format getMessages() {
        return messages;
    }

    public static SfioraBridgeFormatOptions parse(Object rawOptions)
            throws SfioraBridgeOptionsException {
        if (rawOptions != null && !(rawOptions instanceof Map<?, ?>)) {
            throw new SfioraBridgeOptionsException("options must be an object");
        }
        Map<?, ?> options = rawOptions == null
                ? Collections.emptyMap() : (Map<?, ?>) rawOptions;
        for (Object key : options.keySet()) {
            if (!"timeoutMilliseconds".equals(key) && !"messages".equals(key)) {
                throw new SfioraBridgeOptionsException("options contains an unsupported field: " + key);
            }
        }
        NfcWriteConfiguration.Builder configuration = NfcWriteConfiguration.builder();
        if (options.containsKey("timeoutMilliseconds")) {
            Object rawTimeout = options.get("timeoutMilliseconds");
            double timeout = rawTimeout instanceof Number
                    ? ((Number) rawTimeout).doubleValue() : Double.NaN;
            if (!Double.isFinite(timeout) || Math.rint(timeout) != timeout
                    || timeout < NfcWriteConfiguration.MINIMUM_TIMEOUT_MILLIS
                    || timeout > NfcWriteConfiguration.MAXIMUM_TIMEOUT_MILLIS) {
                throw new SfioraBridgeOptionsException(
                        "timeoutMilliseconds must be an integer from 1000 to 60000");
            }
            configuration.timeoutMillis((long) timeout);
        }
        NfcPresentationMessages.Format messages = options.containsKey("messages")
                ? SfioraBridgePresentationMessages.parseFormat(options.get("messages")) : null;
        return new SfioraBridgeFormatOptions(configuration.build(), messages);
    }
}
