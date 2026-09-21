package com.why.fulfillment.inventory.redis;

/** Semantic result states returned by the Redis inventory scripts. */
public enum RedisStockResultStatus {
    RESERVED,
    ALREADY_RESERVED,
    CANCELED,
    COMPENSATED,
    ALREADY_COMPENSATED,
    CONFLICT,
    REJECTED,
    UNKNOWN,
    NO_RESERVATION
}
