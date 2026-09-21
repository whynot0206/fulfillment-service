package com.why.fulfillment.api.inventory;

/** Public inventory snapshot.  It contains no persistence entity fields. */
public record InventoryQueryResponse(Long skuId, Long spuId, Integer stock, Integer lockStock) {
}
