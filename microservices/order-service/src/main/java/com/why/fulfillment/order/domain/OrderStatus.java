package com.why.fulfillment.order.domain;

public enum OrderStatus {
    PENDING_PAYMENT(1),
    PAID(2),
    CANCELED(3),
    CLOSED(4);

    private final int code;

    OrderStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
