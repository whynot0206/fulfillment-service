package com.why.fulfillment.order.task;

import com.why.fulfillment.api.inventory.InventoryClient;
import com.why.fulfillment.api.inventory.InventoryConfirmRequest;
import com.why.fulfillment.api.inventory.InventoryConfirmResponse;
import com.why.fulfillment.order.repository.OrderRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class InventoryConfirmationPublisher {
    private final OrderRepository repository;
    private final InventoryClient inventoryClient;

    public InventoryConfirmationPublisher(OrderRepository repository, InventoryClient inventoryClient) {
        this.repository = repository;
        this.inventoryClient = inventoryClient;
    }

    @Scheduled(fixedDelayString = "${fulfillment.outbox.poll-delay-ms:1000}")
    public void publishReady() {
        repository.recoverStaleConfirmationClaims();
        for (Long eventId : repository.findReadyConfirmationEventIds(50)) {
            repository.claimConfirmationEvent(eventId).ifPresent(orderId -> publish(eventId, orderId));
        }
    }

    private void publish(long eventId, long orderId) {
        try {
            InventoryConfirmResponse response = inventoryClient.confirm(new InventoryConfirmRequest(orderId));
            if (response != null && "CONFIRMED".equalsIgnoreCase(response.status())) {
                repository.markConfirmationSent(eventId);
                return;
            }
            repository.retryConfirmation(eventId,
                    response == null ? "empty inventory confirmation response" : response.error());
        } catch (RuntimeException exception) {
            String message = exception.getMessage();
            repository.retryConfirmation(eventId,
                    message == null || message.isBlank() ? exception.getClass().getSimpleName() : message);
        }
    }
}
