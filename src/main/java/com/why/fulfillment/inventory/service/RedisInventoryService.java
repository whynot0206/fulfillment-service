package com.why.fulfillment.inventory.service;

import java.util.List;

/**
 * Redis inventory boundary used by the asynchronous order persistence path.
 *
 * <p>The Redis Lua implementation is intentionally supplied by the Redis
 * integration owner. This module only needs the compensation operation when a
 * command reaches the dead-letter state. {@link #preDeduct(Long, List)} is a
 * convenience hook for the caller that creates the command and may be
 * implemented by the same adapter.</p>
 */
public interface RedisInventoryService {

    /**
     * Atomically pre-deduct inventory in Redis and return whether it succeeded.
     * The default keeps this persistence module usable while the Redis adapter
     * is being integrated; production callers should provide an implementation.
     */
    default boolean preDeduct(Long orderId, List<StockReservationItem> items) {
        throw new UnsupportedOperationException("Redis pre-deduct is not configured");
    }

    /** Return the Redis reservation when MySQL persistence has reached dead letter. */
    void compensate(Long orderId, List<StockReservationItem> items);
}
