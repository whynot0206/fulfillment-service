package com.why.fulfillment.inventory.redis;

/**
 * Result of initializing one SKU's Redis stock key from MySQL.
 *
 * <p>{@code initialized} means this caller won the SETNX race;
 * {@code alreadyPresent} means another caller (or an earlier call) had
 * already created the key.</p>
 */
public record RedisStockInitializationResult(String key,
                                             Long skuId,
                                             boolean initialized,
                                             boolean alreadyPresent,
                                             Integer stock) {

    public boolean isSuccess() {
        return initialized || alreadyPresent;
    }
}
