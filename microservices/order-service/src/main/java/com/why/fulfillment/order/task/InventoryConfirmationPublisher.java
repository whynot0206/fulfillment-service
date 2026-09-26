package com.why.fulfillment.order.task;

import com.why.fulfillment.api.inventory.InventoryClient;
import com.why.fulfillment.api.inventory.InventoryConfirmRequest;
import com.why.fulfillment.api.inventory.InventoryConfirmResponse;
import com.why.fulfillment.order.repository.ConfirmationOutboxRepository;
import com.why.fulfillment.order.repository.ConfirmationOutboxRepository.ConfirmationClaim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class InventoryConfirmationPublisher {
    private static final Logger log = LoggerFactory.getLogger(InventoryConfirmationPublisher.class);
    private final ConfirmationOutboxRepository repository;
    private final InventoryClient inventoryClient;

    public InventoryConfirmationPublisher(ConfirmationOutboxRepository repository, InventoryClient inventoryClient) {
        this.repository = repository;
        this.inventoryClient = inventoryClient;
    }

    @Scheduled(fixedDelayString = "${fulfillment.outbox.poll-delay-ms:1000}")
    public void publishReady() {
        repository.recoverStaleConfirmationClaims();
        for (Long eventId : repository.findReadyConfirmationEventIds(50)) {
            repository.claimConfirmationEvent(eventId).ifPresent(this::publish);
        }
    }

    private void publish(ConfirmationClaim claim) {
        InventoryConfirmResponse response;
        try {
            response = inventoryClient.confirm(new InventoryConfirmRequest(claim.orderId()));
        } catch (RuntimeException exception) {
            // Do not persist exception messages containing remote URLs or credentials.
            retry(claim, exception.getClass().getSimpleName());
            return;
        }
        if (response != null && "CONFIRMED".equalsIgnoreCase(response.status())) {
            if (!repository.markConfirmationSent(claim.eventId(), claim.leaseOwner())) {
                log.warn("outbox completion lost lease eventId={} orderId={}", claim.eventId(), claim.orderId());
            }
            return;
        }
        retry(claim, response == null ? "empty inventory confirmation response" : response.error());
    }

    private void retry(ConfirmationClaim claim, String error) {
        if (!repository.retryConfirmation(claim.eventId(), claim.leaseOwner(), error)) {
            log.warn("outbox retry lost lease eventId={} orderId={}", claim.eventId(), claim.orderId());
        } else {
            log.warn("outbox confirmation queued for retry or dead-letter review eventId={} orderId={}",
                    claim.eventId(), claim.orderId());
        }
    }
}
