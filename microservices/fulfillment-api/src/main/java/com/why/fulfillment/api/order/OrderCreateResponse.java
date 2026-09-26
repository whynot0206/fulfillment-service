package com.why.fulfillment.api.order;

/**
 * Result of an internal order creation.
 *
 * <p>{@code state} mirrors the states the public order API already returns:
 * {@code RESERVED}, {@code FAILED}, {@code COMPENSATED}, {@code PENDING_COMPENSATION},
 * {@code CONFLICT}. Commerce must treat them differently — {@code RESERVED} clears the cart,
 * {@code FAILED} shows the inventory reason, and {@code PENDING_COMPENSATION} means the
 * outcome is genuinely not known yet and must not be reported to the user as a failure.</p>
 *
 * <p>The read-only {@code resolveCreate} contract additionally returns {@code PAID},
 * {@code RESERVING}, {@code CANCELED}, {@code CLOSED}, or {@code NOT_FOUND}.
 * A canceled order awaiting inventory release still returns {@code PENDING_COMPENSATION};
 * absence is only a read observation, not proof that an in-flight creation cannot arrive.</p>
 *
 * <p>{@code replayed} is true when this call hit an order that already existed with the same
 * payload. It is not an error: it is the expected answer to a retry.</p>
 */
public record OrderCreateResponse(Long orderId, String state, String message, boolean replayed) {

    public boolean reserved() {
        return "RESERVED".equalsIgnoreCase(state);
    }
}
