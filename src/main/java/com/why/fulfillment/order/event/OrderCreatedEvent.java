package com.why.fulfillment.order.event;

import java.time.Duration;

public record OrderCreatedEvent(Long orderId, Duration timeout) {
}
