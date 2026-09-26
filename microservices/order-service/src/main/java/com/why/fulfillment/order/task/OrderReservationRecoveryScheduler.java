package com.why.fulfillment.order.task;

import com.why.fulfillment.order.repository.OrderRepository;
import com.why.fulfillment.order.service.OrderApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recovers ordinary orders left between their local insert and the remote reservation decision. */
@Component
@ConditionalOnProperty(prefix = "fulfillment.order-reservation-recovery", name = "enabled", matchIfMissing = true)
public class OrderReservationRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderReservationRecoveryScheduler.class);
    private static final int BATCH_SIZE = 50;
    private final OrderRepository repository;
    private final OrderApplicationService service;
    private final long graceSeconds;

    public OrderReservationRecoveryScheduler(OrderRepository repository, OrderApplicationService service,
            @Value("${fulfillment.order-reservation-recovery.grace-seconds:60}") long graceSeconds) {
        if (graceSeconds < 1 || graceSeconds > 7 * 24 * 60 * 60) {
            throw new IllegalArgumentException("reservation recovery grace must be between 1 second and 7 days");
        }
        this.repository = repository;
        this.service = service;
        this.graceSeconds = graceSeconds;
    }

    @Scheduled(fixedDelayString = "${fulfillment.order-reservation-recovery.poll-delay-ms:5000}",
            initialDelayString = "${fulfillment.order-reservation-recovery.initial-delay-ms:5000}")
    public void recoverStaleReservations() {
        for (Long orderId : repository.findStaleReservingOrderIds(graceSeconds, BATCH_SIZE)) {
            try {
                service.recoverStaleReservation(orderId, graceSeconds);
            } catch (RuntimeException exception) {
                // Keep other candidates moving; either the original RESERVING fact or the
                // committed compensation fact remains available to a later scan.
                log.warn("Failed to recover stale reservation for order {}", orderId, exception);
            }
        }
    }
}
