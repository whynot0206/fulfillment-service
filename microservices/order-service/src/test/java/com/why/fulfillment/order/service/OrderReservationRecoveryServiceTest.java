package com.why.fulfillment.order.service;

import com.why.fulfillment.api.inventory.InventoryClient;
import com.why.fulfillment.api.inventory.InventoryReleaseRequest;
import com.why.fulfillment.api.inventory.InventoryReleaseResponse;
import com.why.fulfillment.api.inventory.InventoryReserveResponse;
import com.why.fulfillment.order.domain.OrderRecord;
import com.why.fulfillment.order.domain.OrderStatus;
import com.why.fulfillment.order.domain.ReservationStatus;
import com.why.fulfillment.order.repository.OrderRepository;
import feign.FeignException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderReservationRecoveryServiceTest {

    private final OrderRepository repository = mock(OrderRepository.class);
    private final InventoryClient inventory = mock(InventoryClient.class);
    private final OrderApplicationService service = new OrderApplicationService(repository, inventory);
    private final OrderApplicationService.CreateOrderCommand command =
            new OrderApplicationService.CreateOrderCommand(10L, 20L, BigDecimal.TEN, 1800L,
                    List.of(new OrderApplicationService.OrderItemCommand(1001L, 1L, 2, new BigDecimal("5.00"))));

    @Test
    void staleRecoveryCommitsCancellationBeforeReleasingInventory() {
        when(repository.markStaleReservingForCompensation(10L, 60L)).thenReturn(true);
        when(inventory.release(any())).thenReturn(InventoryReleaseResponse.released());

        assertThat(service.recoverStaleReservation(10L, 60L)).isTrue();

        InOrder sequence = inOrder(repository, inventory);
        sequence.verify(repository).markStaleReservingForCompensation(10L, 60L);
        sequence.verify(inventory).release(new InventoryReleaseRequest(10L));
        sequence.verify(repository).markCompensatedIfPending(10L, "background compensation released inventory");
        verify(inventory, never()).reserve(any());
    }

    @Test
    void losingStaleRecoveryCasDoesNotCallInventory() {
        when(repository.markStaleReservingForCompensation(10L, 60L)).thenReturn(false);

        assertThat(service.recoverStaleReservation(10L, 60L)).isFalse();

        verifyNoInteractions(inventory);
        verify(repository, never()).markCompensationPending(anyLong(), any());
    }

    @Test
    void failedCancellationWriteCannotReleaseInventory() {
        when(repository.markStaleReservingForCompensation(10L, 60L))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.recoverStaleReservation(10L, 60L))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(inventory);
    }

    @Test
    void failedReleaseKeepsTheDurableCancellationRetryable() {
        when(repository.markStaleReservingForCompensation(10L, 60L)).thenReturn(true);
        when(inventory.release(any())).thenReturn(InventoryReleaseResponse.failed("release result unknown"));

        assertThat(service.recoverStaleReservation(10L, 60L)).isTrue();

        verify(repository).markCompensationPending(10L, "background compensation failed: release result unknown");
        verify(repository, never()).markCompensatedIfPending(anyLong(), any());
    }

    @Test
    void interruptedReleaseKeepsTheDurableCancellationRetryable() {
        when(repository.markStaleReservingForCompensation(10L, 60L)).thenReturn(true);
        when(inventory.release(any())).thenThrow(new IllegalStateException("connection lost"));

        assertThat(service.recoverStaleReservation(10L, 60L)).isTrue();

        verify(repository).markCompensationPending(10L, "background compensation call failed: IllegalStateException");
        verify(repository, never()).markCompensatedIfPending(anyLong(), any());
    }

    @Test
    void invalidOrderIsRejectedBeforeAccessingDependencies() {
        assertThatThrownBy(() -> service.recoverStaleReservation(0L, 60L))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, inventory);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING_PAYMENT", "PAID"})
    void lateUnknownResponseCannotReleaseAnAlreadyReservedOrPaidOrder(OrderStatus status) {
        when(inventory.reserve(any())).thenReturn(InventoryReserveResponse.unknown("response lost"));
        when(repository.markReservingForCompensation(eq(10L), any())).thenReturn(false);
        when(repository.find(10L)).thenReturn(Optional.of(existing(status, ReservationStatus.RESERVED)));

        assertThat(service.createPending(command).state()).isEqualTo("RESERVED");

        verify(inventory, never()).release(any());
        verify(repository, never()).markCompensationPending(anyLong(), any());
    }

    @Test
    void lateRpcExceptionCannotReleaseAPaidOrder() {
        when(inventory.reserve(any())).thenThrow(new IllegalStateException("late timeout"));
        when(repository.markReservingForCompensation(eq(10L), any())).thenReturn(false);
        when(repository.find(10L)).thenReturn(Optional.of(existing(OrderStatus.PAID, ReservationStatus.RESERVED)));

        assertThat(service.createPending(command).state()).isEqualTo("RESERVED");

        verify(inventory, never()).release(any());
    }

    @Test
    void losingUnknownDecisionToAnotherCancellationDoesNotIssueAnotherImmediateRelease() {
        when(inventory.reserve(any())).thenReturn(null);
        when(repository.markReservingForCompensation(eq(10L), any())).thenReturn(false);
        when(repository.find(10L)).thenReturn(Optional.of(existing(
                OrderStatus.CANCELED, ReservationStatus.PENDING_COMPENSATION)));

        assertThat(service.createPending(command).state()).isEqualTo("PENDING_COMPENSATION");

        verify(inventory, never()).release(any());
    }

    @Test
    void unknownDecisionIsPersistedBeforeTheFirstRelease() {
        when(inventory.reserve(any())).thenReturn(InventoryReserveResponse.unknown("response lost"));
        when(repository.markReservingForCompensation(eq(10L), any())).thenReturn(true);
        when(inventory.release(any())).thenReturn(InventoryReleaseResponse.released());

        assertThat(service.createPending(command).state()).isEqualTo("COMPENSATED");

        InOrder sequence = inOrder(repository, inventory);
        sequence.verify(repository).markReservingForCompensation(eq(10L), any());
        sequence.verify(inventory).release(new InventoryReleaseRequest(10L));
        sequence.verify(repository).markCompensatedIfPending(eq(10L), any());
        verify(repository, never()).updateReservation(eq(10L), eq(ReservationStatus.COMPENSATED), any());
    }

    @Test
    void lateDeterministicFeignRejectionReturnsTheWinningPaidState() {
        FeignException rejected = mock(FeignException.class);
        when(rejected.status()).thenReturn(400);
        when(rejected.getMessage()).thenReturn("late client rejection");
        when(inventory.reserve(any())).thenThrow(rejected);
        when(repository.updateReservation(eq(10L), eq(ReservationStatus.FAILED), any())).thenReturn(false);
        when(repository.find(10L)).thenReturn(Optional.of(existing(OrderStatus.PAID, ReservationStatus.RESERVED)));

        assertThat(service.createPending(command).state()).isEqualTo("RESERVED");

        verify(inventory, never()).release(any());
        verify(repository, never()).markReservingForCompensation(anyLong(), any());
    }

    private static OrderRecord existing(OrderStatus status, ReservationStatus reservation) {
        return new OrderRecord(10L, 20L, BigDecimal.TEN, 1800L, status, reservation, null,
                status == OrderStatus.PAID ? "already-paid" : null, null, null, List.of());
    }
}
