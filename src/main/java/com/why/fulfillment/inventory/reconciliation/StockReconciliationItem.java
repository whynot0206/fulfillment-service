package com.why.fulfillment.inventory.reconciliation;

/** One point-in-time comparison between MySQL and Redis available stock. */
public record StockReconciliationItem(
        Long skuId,
        int mysqlAvailableStock,
        int pendingRedisDeduction,
        int expectedRedisStock,
        Long actualRedisStock,
        Status status) {

    public enum Status {
        CONSISTENT,
        DIFFERENT,
        REDIS_KEY_MISSING
    }
}
