package com.why.fulfillment.api.inventory;

/** Result of an idempotent inventory confirmation attempt. */
public record InventoryConfirmResponse(String status, String error) {

    public static InventoryConfirmResponse confirmed() {
        return new InventoryConfirmResponse("CONFIRMED", null);
    }

    public static InventoryConfirmResponse failed(String error) {
        return new InventoryConfirmResponse("FAILED", error);
    }
}
