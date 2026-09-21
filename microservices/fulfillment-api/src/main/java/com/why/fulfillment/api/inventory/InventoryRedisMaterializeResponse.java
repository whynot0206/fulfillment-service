package com.why.fulfillment.api.inventory;

public record InventoryRedisMaterializeResponse(String status, String error) {
    public static InventoryRedisMaterializeResponse materialized() {
        return new InventoryRedisMaterializeResponse("MATERIALIZED", null);
    }

    public static InventoryRedisMaterializeResponse conflict(String error) {
        return new InventoryRedisMaterializeResponse("CONFLICT", error);
    }

    public static InventoryRedisMaterializeResponse failed(String error) {
        return new InventoryRedisMaterializeResponse("FAILED", error);
    }
}
