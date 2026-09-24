package com.why.fulfillment.api.order;

import java.math.BigDecimal;

/** Minimal internal view for a payment simulation; Order remains the status owner. */
public record OrderPaymentView(long orderId, long userId, BigDecimal amount, String status) {
}
