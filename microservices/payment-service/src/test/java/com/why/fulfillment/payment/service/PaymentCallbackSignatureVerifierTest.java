package com.why.fulfillment.payment.service;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentCallbackSignatureVerifierTest {
    private static final Instant NOW = Instant.ofEpochSecond(2_000_000_000L);
    private final PaymentCallbackSignatureVerifier verifier =
            new PaymentCallbackSignatureVerifier("callback-secret", 300,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void acceptsValidSignatureAndRejectsExpiredTimestamp() throws Exception {
        long timestamp = NOW.getEpochSecond();
        String signature = sign("callback-secret", "42\ntrade-1\n" + timestamp);

        assertThat(verifier.verify(42L, "trade-1", timestamp, signature)).isTrue();
        assertThat(verifier.verify(42L, "trade-1", timestamp - 301, signature)).isFalse();
    }

    private String sign(String secret, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
}
