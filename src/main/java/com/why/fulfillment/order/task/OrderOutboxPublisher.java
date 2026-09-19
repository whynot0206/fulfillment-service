package com.why.fulfillment.order.task;

import com.why.fulfillment.inventory.service.InventoryReservationService;
import com.why.fulfillment.order.entity.OrderOutboxEvent;
import com.why.fulfillment.order.mapper.OrderOutboxEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** 本地消息表发布器：至少一次投递，消费端必须幂等。 */
@Component
@ConditionalOnProperty(name = "fulfillment.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class OrderOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderOutboxPublisher.class);
    private static final int BATCH_SIZE = 100;
    private static final int MAX_ERROR_LENGTH = 500;

    private final OrderOutboxEventMapper eventMapper;
    private final InventoryReservationService inventoryReservationService;

    public OrderOutboxPublisher(OrderOutboxEventMapper eventMapper,
                                InventoryReservationService inventoryReservationService) {
        this.eventMapper = eventMapper;
        this.inventoryReservationService = inventoryReservationService;
    }

    @Scheduled(fixedDelayString = "${fulfillment.outbox.publish-ms:1000}")
    public void publishReadyEvents() {
        for (OrderOutboxEvent event : eventMapper.listReady(BATCH_SIZE)) {
            if (eventMapper.claim(event.getEventId()) == 1) {
                publish(event);
            }
        }
    }

    private void publish(OrderOutboxEvent event) {
        try {
            if (!OrderOutboxEvent.PAYMENT_CONFIRMED.equals(event.getEventType())) {
                throw new IllegalArgumentException("unsupported outbox event: " + event.getEventType());
            }
            if (event.getPayload() == null || event.getPayload().isBlank()) {
                throw new IllegalArgumentException("invalid payment event payload");
            }
            Long orderId;
            try {
                orderId = Long.valueOf(event.getPayload());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("invalid payment event payload", exception);
            }
            inventoryReservationService.confirm(orderId);
            eventMapper.markSent(event.getEventId());
        } catch (Exception exception) {
            int retryCount = Objects.requireNonNullElse(event.getRetryCount(), 0) + 1;
            long backoffSeconds = Math.min(300L, 1L << Math.min(retryCount, 8));
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage();
            eventMapper.reschedule(event.getEventId(), retryCount,
                    LocalDateTime.now().plusSeconds(backoffSeconds),
                    message.substring(0, Math.min(MAX_ERROR_LENGTH, message.length())));
            log.warn("Failed to publish outbox event {}, retry {}", event.getEventId(), retryCount,
                    exception);
        }
    }
}
