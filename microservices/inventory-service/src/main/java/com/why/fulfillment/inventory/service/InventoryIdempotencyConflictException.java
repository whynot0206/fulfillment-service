package com.why.fulfillment.inventory.service;

/** A reused orderId was supplied with a different reservation payload. */
public class InventoryIdempotencyConflictException extends RuntimeException {

    public InventoryIdempotencyConflictException(String message) {
        super(message);
    }
}
