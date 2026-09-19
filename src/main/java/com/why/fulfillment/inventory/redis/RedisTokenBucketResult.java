package com.why.fulfillment.inventory.redis;

import java.math.BigDecimal;

/**
 * Explicit result of one atomic token bucket calculation.
 */
public record RedisTokenBucketResult(boolean allowed,
                                     RedisTokenBucketStatus status,
                                     BigDecimal remainingTokens,
                                     long capacity,
                                     BigDecimal refillRatePerSecond,
                                     long requestedTokens) {

    public RedisTokenBucketResult {
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (remainingTokens == null) {
            remainingTokens = BigDecimal.ZERO;
        }
        if (refillRatePerSecond == null) {
            refillRatePerSecond = BigDecimal.ZERO;
        }
    }

    public boolean isAllowed() {
        return allowed;
    }
}
