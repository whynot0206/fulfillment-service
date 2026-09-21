package com.why.fulfillment.api.inventory;

/** Result of an idempotent inventory reservation attempt. */
public record InventoryReserveResponse(String status, String error) {

    public static InventoryReserveResponse reserved() {
        return new InventoryReserveResponse("RESERVED", null);
    }

    public static InventoryReserveResponse rejected(String error) {
        return new InventoryReserveResponse("REJECTED", error);
    }

    public static InventoryReserveResponse unknown(String error) {
        return new InventoryReserveResponse("UNKNOWN", error);
    }
}
