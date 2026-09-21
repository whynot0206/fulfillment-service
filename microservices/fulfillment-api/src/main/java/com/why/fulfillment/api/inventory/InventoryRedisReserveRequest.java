package com.why.fulfillment.api.inventory;

import java.util.List;

/** Request for the Redis fast inventory reservation path. */
public record InventoryRedisReserveRequest(Long orderId, List<InventoryReserveItem> items) {
}
