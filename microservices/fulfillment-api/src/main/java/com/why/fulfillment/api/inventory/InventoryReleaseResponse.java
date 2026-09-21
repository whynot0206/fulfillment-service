package com.why.fulfillment.api.inventory;

/** Result of an idempotent inventory release attempt. */
public record InventoryReleaseResponse(String status, String error) {

    public static InventoryReleaseResponse released() {
        return new InventoryReleaseResponse("RELEASED", null);
    }

    public static InventoryReleaseResponse failed(String error) {
        return new InventoryReleaseResponse("FAILED", error);
    }
}
