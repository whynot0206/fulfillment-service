package com.why.fulfillment.api.inventory;

import java.util.List;

public record InventoryReserveRequest(Long orderId, List<InventoryReserveItem> items) {
}
