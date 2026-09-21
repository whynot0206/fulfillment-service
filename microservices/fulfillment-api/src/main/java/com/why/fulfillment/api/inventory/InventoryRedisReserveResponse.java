package com.why.fulfillment.api.inventory;

/** Result of an idempotent Redis inventory reservation attempt. */
public record InventoryRedisReserveResponse(String status, String error) {

    public static InventoryRedisReserveResponse reserved() {
        return new InventoryRedisReserveResponse("RESERVED", null);
    }

    public static InventoryRedisReserveResponse alreadyReserved() {
        return new InventoryRedisReserveResponse("ALREADY_RESERVED", null);
    }

    public static InventoryRedisReserveResponse canceled(String error) {
        return new InventoryRedisReserveResponse("CANCELED", error);
    }

    public static InventoryRedisReserveResponse conflict(String error) {
        return new InventoryRedisReserveResponse("CONFLICT", error);
    }

    public static InventoryRedisReserveResponse rejected(String error) {
        return new InventoryRedisReserveResponse("REJECTED", error);
    }

    public static InventoryRedisReserveResponse unknown(String error) {
        return new InventoryRedisReserveResponse("UNKNOWN", error);
    }
}
