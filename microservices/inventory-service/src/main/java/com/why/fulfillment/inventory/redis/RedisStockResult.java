package com.why.fulfillment.inventory.redis;

import java.util.Map;

/** Immutable result of one Redis inventory operation. */
public record RedisStockResult(RedisStockResultStatus status,
                               String error,
                               Map<Long, Integer> quantities) {

    public RedisStockResult {
        quantities = quantities == null ? Map.of() : Map.copyOf(quantities);
    }

    public boolean accepted() {
        return status == RedisStockResultStatus.RESERVED
                || status == RedisStockResultStatus.ALREADY_RESERVED
                || status == RedisStockResultStatus.COMPENSATED
                || status == RedisStockResultStatus.ALREADY_COMPENSATED
                || status == RedisStockResultStatus.NO_RESERVATION;
    }

    public static RedisStockResult of(RedisStockResultStatus status,
                                      String error,
                                      Map<Long, Integer> quantities) {
        return new RedisStockResult(status, error, quantities);
    }
}
