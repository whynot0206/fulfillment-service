package com.why.fulfillment.inventory.service;

/** Indicates an unexpected failure while changing inventory rows. */
public class InventoryStockException extends RuntimeException {

    public InventoryStockException(String message) {
        super(message);
    }
}
