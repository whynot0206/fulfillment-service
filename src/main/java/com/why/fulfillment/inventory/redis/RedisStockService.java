package com.why.fulfillment.inventory.redis;

import com.why.fulfillment.inventory.service.StockReservationItem;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Redis side of stock pre-deduction.
 *
 * <p>The service only owns Redis stock keys.  It does not write MySQL lock
 * rows or alter the existing order/inventory service.  A successful reserve
 * must be paired with a rollback when the downstream operation cannot be
 * completed.</p>
 */
public interface RedisStockService {

    /**
     * Reserve every requested SKU in one Lua execution.
     *
     * @param quantities SKU id to positive quantity; duplicate SKU ids should
     *                   be merged by the caller or by the list overload
     */
    RedisStockResult reserve(Map<Long, Integer> quantities);

    /** Convenience overload for the domain reservation item already used by the service. */
    RedisStockResult reserve(List<StockReservationItem> items);

    /** Return exactly the quantities supplied by a successful reservation. */
    RedisStockResult rollback(Map<Long, Integer> quantities);

    /** Convenience overload for the domain reservation item already used by the service. */
    RedisStockResult rollback(List<StockReservationItem> items);

    /**
     * Idempotent reservation keyed by a business operation such as order id.
     * The marker and all stock mutations are written by one Lua invocation.
     */
    RedisStockResult reserveOnce(String operationId, List<StockReservationItem> items);

    /**
     * Idempotent compensation keyed by the same business operation.  Repeating
     * the call observes the marker and does not add stock a second time.
     */
    RedisStockResult rollbackOnce(String operationId, List<StockReservationItem> items);

    /**
     * Initialize one key from {@code SkuStockMapper} only when it does not yet
     * exist.  Concurrent callers cannot overwrite a value created by another
     * caller because the write uses SETNX semantics.
     */
    RedisStockInitializationResult initializeIfAbsent(Long skuId);

    /** Initialize a collection of SKU keys, preserving one result per SKU. */
    Map<Long, RedisStockInitializationResult> initializeIfAbsent(Collection<Long> skuIds);

    /** Read a Redis stock value without initializing or mutating it. */
    OptionalLong readStock(Long skuId);

    /**
     * Apply a reconciliation correction only when the value is unchanged
     * since the caller's read.  Returns false when a concurrent operation won
     * the race or when the key is absent.
     */
    boolean compareAndSetStockForReconciliation(Long skuId,
                                                int expectedStock,
                                                int correctedStock);

    /** Return the physical Redis key used for one SKU. */
    String stockKey(Long skuId);
}
