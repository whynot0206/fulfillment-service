package com.why.fulfillment.inventory.redis;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Result of an atomic multi-SKU reserve or rollback operation.
 *
 * @param success whether the requested operation was applied
 * @param status a machine-readable outcome
 * @param failedSkuId SKU that caused a failed operation, when Redis returned
 *                    an index for one of the supplied keys
 * @param quantities normalized quantities supplied to Redis
 */
public record RedisStockResult(boolean success,
                               RedisStockResultStatus status,
                               Long failedSkuId,
                               Map<Long, Integer> quantities) {

    public RedisStockResult {
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        quantities = quantities == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(quantities));
    }

    public static RedisStockResult success(Map<Long, Integer> quantities) {
        return new RedisStockResult(true, RedisStockResultStatus.SUCCESS, null, quantities);
    }

    public static RedisStockResult failure(RedisStockResultStatus status,
                                           Long failedSkuId,
                                           Map<Long, Integer> quantities) {
        if (status == RedisStockResultStatus.SUCCESS) {
            throw new IllegalArgumentException("a failed result cannot have SUCCESS status");
        }
        return new RedisStockResult(false, status, failedSkuId, quantities);
    }

    /** Alias that reads naturally at call sites and in tests. */
    public boolean isSuccess() {
        return success;
    }
}
