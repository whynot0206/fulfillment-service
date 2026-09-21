package com.why.fulfillment.inventory.service;

public class InventorySkuNotFoundException extends RuntimeException {

    public InventorySkuNotFoundException(Long skuId) {
        super("SKU does not exist: " + skuId);
    }
}
