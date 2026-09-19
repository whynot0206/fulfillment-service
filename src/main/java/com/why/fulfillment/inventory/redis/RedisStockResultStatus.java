package com.why.fulfillment.inventory.redis;

/**
 * Outcome of a Redis stock operation.
 *
 * <p>The status is deliberately part of the return value.  Callers can tell
 * an ordinary inventory rejection from an unavailable Redis instance without
 * parsing exception messages.</p>
 */
public enum RedisStockResultStatus {
    SUCCESS,
    INVALID_REQUEST,
    SKU_NOT_FOUND,
    INSUFFICIENT_STOCK,
    IDEMPOTENCY_CONFLICT,
    REDIS_ERROR
}
