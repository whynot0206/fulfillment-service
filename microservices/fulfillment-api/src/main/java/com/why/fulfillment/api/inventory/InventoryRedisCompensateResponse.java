package com.why.fulfillment.api.inventory;

/** Result of an idempotent Redis inventory compensation attempt. */
public record InventoryRedisCompensateResponse(String status, String error) {

    public static InventoryRedisCompensateResponse compensated() {
        return new InventoryRedisCompensateResponse("COMPENSATED", null);
    }

    public static InventoryRedisCompensateResponse alreadyCompensated() {
        return new InventoryRedisCompensateResponse("ALREADY_COMPENSATED", null);
    }

    public static InventoryRedisCompensateResponse conflict(String error) {
        return new InventoryRedisCompensateResponse("CONFLICT", error);
    }

    public static InventoryRedisCompensateResponse rejected(String error) {
        return new InventoryRedisCompensateResponse("REJECTED", error);
    }

    public static InventoryRedisCompensateResponse unknown(String error) {
        return new InventoryRedisCompensateResponse("UNKNOWN", error);
    }
}
