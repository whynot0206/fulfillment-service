package com.why.fulfillment.order.task;

import com.why.fulfillment.api.inventory.InventoryClient;
import com.why.fulfillment.api.inventory.InventoryConfirmResponse;
import com.why.fulfillment.order.repository.ConfirmationOutboxRepository;
import com.why.fulfillment.order.repository.ConfirmationOutboxRepository.ConfirmationClaim;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InventoryConfirmationPublisherTest {
    private final ConfirmationOutboxRepository repository = mock(ConfirmationOutboxRepository.class);
    private final InventoryClient inventory = mock(InventoryClient.class);
    private final InventoryConfirmationPublisher publisher =
            new InventoryConfirmationPublisher(repository, inventory);

    @Test
    void confirmedEventIsMarkedSent() {
        when(repository.findReadyConfirmationEventIds(50)).thenReturn(List.of(7L));
        when(repository.claimConfirmationEvent(7L)).thenReturn(Optional.of(new ConfirmationClaim(7L, 10L, "owner-a")));
        when(repository.markConfirmationSent(7L, "owner-a")).thenReturn(true);
        when(inventory.confirm(any())).thenReturn(InventoryConfirmResponse.confirmed());

        publisher.publishReady();

        verify(repository).markConfirmationSent(7L, "owner-a");
        verify(repository, never()).retryConfirmation(eq(7L), any(), any());
    }

    @Test
    void failedConfirmationReturnsEventToRetryQueue() {
        when(repository.findReadyConfirmationEventIds(50)).thenReturn(List.of(7L));
        when(repository.claimConfirmationEvent(7L)).thenReturn(Optional.of(new ConfirmationClaim(7L, 10L, "owner-a")));
        when(repository.retryConfirmation(7L, "owner-a", "IllegalStateException")).thenReturn(true);
        when(inventory.confirm(any())).thenThrow(new IllegalStateException("offline"));

        publisher.publishReady();

        verify(repository).retryConfirmation(7L, "owner-a", "IllegalStateException");
        verify(repository, never()).markConfirmationSent(eq(7L), any());
    }

    @Test
    void lateSuccessDoesNotRequeueOrOverwriteAClaimNowOwnedByAnotherWorker() {
        when(repository.findReadyConfirmationEventIds(50)).thenReturn(List.of(7L));
        when(repository.claimConfirmationEvent(7L)).thenReturn(Optional.of(new ConfirmationClaim(7L, 10L, "old-owner")));
        when(inventory.confirm(any())).thenReturn(InventoryConfirmResponse.confirmed());
        when(repository.markConfirmationSent(7L, "old-owner")).thenReturn(false);

        publisher.publishReady();

        verify(repository).markConfirmationSent(7L, "old-owner");
        verify(repository, never()).retryConfirmation(eq(7L), any(), any());
    }

    @Test
    void lateFailureUsesOnlyOriginalTokenWhenNewOwnerHasAlreadySentEvent() {
        when(repository.findReadyConfirmationEventIds(50)).thenReturn(List.of(7L));
        when(repository.claimConfirmationEvent(7L)).thenReturn(Optional.of(new ConfirmationClaim(7L, 10L, "old-owner")));
        when(inventory.confirm(any())).thenThrow(new IllegalStateException("offline"));
        when(repository.retryConfirmation(7L, "old-owner", "IllegalStateException")).thenReturn(false);

        publisher.publishReady();

        verify(repository).retryConfirmation(7L, "old-owner", "IllegalStateException");
        verify(repository, never()).markConfirmationSent(eq(7L), any());
    }

    @Test
    void unclaimedEventIsNotDelivered() {
        when(repository.findReadyConfirmationEventIds(50)).thenReturn(List.of(7L));
        when(repository.claimConfirmationEvent(7L)).thenReturn(Optional.empty());

        publisher.publishReady();

        verify(repository).recoverStaleConfirmationClaims();
        verifyNoInteractions(inventory);
    }
}
