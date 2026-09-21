package com.why.fulfillment.api.inventory;

/**
 * The data needed to reserve one SKU.  This is deliberately a transport
 * object; inventory entities are kept inside inventory-service.
 */
public record InventoryReserveItem(Long skuId, Long spuId, Integer count) {
}
