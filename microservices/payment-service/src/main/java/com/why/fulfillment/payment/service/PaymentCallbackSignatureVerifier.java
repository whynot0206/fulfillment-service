package com.why.fulfillment.payment.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;

@Component
public class PaymentCallbackSignatureVerifier {
    private final byte[] secret;
    private final long maxSkewSeconds;
    private final Clock clock;

    @Autowired
    public PaymentCallbackSignatureVerifier(@Value("${payment.callback.secret}") String secret,
                                            @Value("${payment.callback.max-skew-seconds:300}") long maxSkewSeconds) {
        this(secret, maxSkewSeconds, Clock.systemUTC());
    }

    PaymentCallbackSignatureVerifier(String secret, long maxSkewSeconds, Clock clock) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("PAYMENT_CALLBACK_SECRET must be configured");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.maxSkewSeconds = maxSkewSeconds;
        this.clock = clock;
    }

    public boolean verify(long orderId, String outTradeNo, long timestamp, String signature) {
        if (signature == null || Math.abs(Instant.now(clock).getEpochSecond() - timestamp) > maxSkewSeconds) {
            return false;
        }
        String canonical = orderId + "\n" + outTradeNo + "\n" + timestamp;
        byte[] expected = hmac(canonical);
        try {
            return MessageDigest.isEqual(expected, HexFormat.of().parseHex(signature));
        } catch (IllegalArgumentException malformedHex) {
            return false;
        }
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot calculate callback signature", exception);
        }
    }
}
