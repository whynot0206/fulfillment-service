package com.why.fulfillment.order.task;

import com.why.fulfillment.api.inventory.InventoryClient;
import com.why.fulfillment.api.inventory.InventoryConfirmResponse;
import com.why.fulfillment.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryConfirmationPublisherTest {
    private final OrderRepository repository = mock(OrderRepository.class);
    private final InventoryClient inventory = mock(InventoryClient.class);
    private final InventoryConfirmationPublisher publisher =
            new InventoryConfirmationPublisher(repository, inventory);

    @Test
    void confirmedEventIsMarkedSent() {
        when(repository.findReadyConfirmationEventIds(50)).thenReturn(List.of(7L));
        when(repository.claimConfirmationEvent(7L)).thenReturn(Optional.of(10L));
        when(inventory.confirm(any())).thenReturn(InventoryConfirmResponse.confirmed());

        publisher.publishReady();

        verify(repository).markConfirmationSent(7L);
        verify(repository, never()).retryConfirmation(eq(7L), any());
    }

    @Test
    void failedConfirmationReturnsEventToRetryQueue() {
        when(repository.findReadyConfirmationEventIds(50)).thenReturn(List.of(7L));
        when(repository.claimConfirmationEvent(7L)).thenReturn(Optional.of(10L));
        when(inventory.confirm(any())).thenThrow(new IllegalStateException("offline"));

        publisher.publishReady();

        verify(repository).retryConfirmation(7L, "offline");
        verify(repository, never()).markConfirmationSent(7L);
    }
}
