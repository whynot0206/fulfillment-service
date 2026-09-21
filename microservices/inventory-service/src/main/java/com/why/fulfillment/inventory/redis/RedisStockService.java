package com.why.fulfillment.inventory.redis;

import com.why.fulfillment.api.inventory.InventoryReserveItem;

import java.util.List;
import java.util.OptionalLong;

/** Redis inventory boundary used by the fast order path and compensation. */
public interface RedisStockService {

    /** Reserve all SKU quantities atomically and idempotently for an order. */
    RedisStockResult reserve(Long orderId, List<InventoryReserveItem> items);

    /** Force compensation and write a cancellation tombstone when needed. */
    RedisStockResult compensate(Long orderId, List<InventoryReserveItem> items);

    /**
     * Compensate only when a Redis reservation marker already exists. Ordinary
     * MySQL orders call this method so they do not create Redis tombstones.
     */
    RedisStockResult compensateIfReserved(Long orderId, List<InventoryReserveItem> items);

    /** Read a key without initializing or changing it. */
    OptionalLong readStock(Long skuId);

    /** Physical Redis key used for one SKU. */
    String stockKey(Long skuId);

    static String reservationMarkerKey(String orderId) {
        return RedisStockServiceImpl.reservationMarkerKey(orderId);
    }

    static String compensationMarkerKey(String orderId) {
        return RedisStockServiceImpl.compensationMarkerKey(orderId);
    }
}
