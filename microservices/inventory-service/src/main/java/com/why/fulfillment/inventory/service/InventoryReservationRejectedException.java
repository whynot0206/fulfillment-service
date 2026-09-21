package com.why.fulfillment.inventory.service;

/** A valid request that cannot be applied to the current reservation state. */
public class InventoryReservationRejectedException extends RuntimeException {

    public InventoryReservationRejectedException(String message) {
        super(message);
    }
}
