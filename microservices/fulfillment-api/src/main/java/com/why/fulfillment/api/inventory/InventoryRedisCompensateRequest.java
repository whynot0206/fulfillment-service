package com.why.fulfillment.api.inventory;

import java.util.List;

/** Request for the Redis fast inventory compensation path. */
public record InventoryRedisCompensateRequest(Long orderId, List<InventoryReserveItem> items) {
}
