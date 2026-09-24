package com.why.fulfillment.api.order;

import java.math.BigDecimal;
import java.util.List;

/**
 * Internal order-creation contract used by Commerce checkout.
 *
 * <p>{@code orderId} is supplied by the caller on purpose. It is the idempotency key: Order's
 * primary key rejects the second insert, and Order then compares the stored payload with the
 * incoming one to tell a safe retry apart from an id collision. A server-generated id would
 * make every retry produce a new order.</p>
 *
 * <p>{@code totalAmount} is checked against the sum of the items by the receiving side. Sending
 * it is not redundant: it lets Order reject a caller whose own arithmetic disagrees with the
 * lines it sent, instead of silently charging the recomputed number.</p>
 */
public record OrderCreateRequest(Long orderId,
                                 Long userId,
                                 BigDecimal totalAmount,
                                 Long timeoutSeconds,
                                 List<OrderCreateItem> items) {
}
