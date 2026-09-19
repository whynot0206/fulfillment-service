package com.why.fulfillment.inventory.redis;

/** Outcome of a token bucket request. */
public enum RedisTokenBucketStatus {
    ALLOWED,
    REJECTED,
    INVALID_REQUEST,
    REDIS_ERROR
}
