package com.why.fulfillment.api.inventory;

import java.util.List;

/** Marks a Redis reservation as represented by committed MySQL inventory. */
public record InventoryRedisMaterializeRequest(Long orderId, List<InventoryReserveItem> items) { }
