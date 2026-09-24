package com.why.fulfillment.order.service;

import com.why.fulfillment.api.inventory.InventoryClient;
import com.why.fulfillment.api.inventory.InventoryReleaseResponse;
import com.why.fulfillment.api.inventory.InventoryReserveResponse;
import com.why.fulfillment.order.domain.OrderItemRecord;
import com.why.fulfillment.order.domain.OrderRecord;
import com.why.fulfillment.order.domain.OrderStatus;
import com.why.fulfillment.order.domain.ReservationStatus;
import com.why.fulfillment.order.repository.OrderRepository;
import feign.FeignException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderApplicationServiceTest {

    private final OrderRepository repository = mock(OrderRepository.class);
    private final InventoryClient inventory = mock(InventoryClient.class);
    private final OrderApplicationService service = new OrderApplicationService(repository, inventory);
    private final OrderApplicationService.CreateOrderCommand command =
            new OrderApplicationService.CreateOrderCommand(10L, 20L, BigDecimal.TEN,
                    1800L,
                    List.of(new OrderApplicationService.OrderItemCommand(
                            1001L, 1L, 2, new BigDecimal("5.00"))));

    @Test
    void deterministicInventoryRejectionMarksOrderFailedWithoutRelease() {
        when(inventory.reserve(any())).thenReturn(InventoryReserveResponse.rejected("insufficient stock"));
        when(repository.updateReservation(10L, ReservationStatus.FAILED, "insufficient stock"))
                .thenReturn(true);

        OrderApplicationService.CreateOrderResult result = service.createPending(command);

        assertThat(result.state()).isEqualTo("FAILED");
        verify(repository).updateReservation(10L, ReservationStatus.FAILED, "insufficient stock");
        verify(inventory, never()).release(any());
    }

    @Test
    void remoteFailureReleasesAndRecordsCompensation() {
        FeignException unavailable = mock(FeignException.class);
        when(unavailable.status()).thenReturn(503);
        when(unavailable.getMessage()).thenReturn("inventory unavailable");
        when(inventory.reserve(any())).thenThrow(unavailable);
        when(inventory.release(any())).thenReturn(InventoryReleaseResponse.released());

        OrderApplicationService.CreateOrderResult result = service.createPending(command);

        assertThat(result.state()).isEqualTo("COMPENSATED");
        verify(repository).updateReservation(eq(10L), eq(ReservationStatus.COMPENSATED), any());
        verify(inventory).release(any());
    }

    @Test
    void failedCompensationLeavesAnExplicitRetryableState() {
        when(inventory.reserve(any())).thenReturn(InventoryReserveResponse.unknown("reserve result lost"));
        when(inventory.release(any())).thenReturn(InventoryReleaseResponse.failed("inventory timeout"));

        OrderApplicationService.CreateOrderResult result = service.createPending(command);

        assertThat(result.state()).isEqualTo("PENDING_COMPENSATION");
        verify(repository).markCompensationPending(eq(10L), any());
    }

    @Test
    void backgroundRetryCompletesPendingCompensation() {
        when(inventory.release(any())).thenReturn(InventoryReleaseResponse.released());

        service.retryPendingCompensation(10L);

        verify(repository).markCompensatedIfPending(10L, "background compensation released inventory");
    }

    @Test
    void paymentTradeNumberOwnedByAnotherOrderIsRejected() {
        when(repository.markPaidIfPending(10L, "trade-conflict"))
                .thenThrow(new DuplicateKeyException("duplicate out_trade_no"));

        assertThat(service.markPaid(10L, "trade-conflict")).isFalse();
    }

    @Test
    void sameCompletedRequestReturnsIdempotentSuccessWithoutReservingAgain() {
        doThrow(new DuplicateKeyException("duplicate order"))
                .when(repository).insertPending(eq(10L), eq(20L), eq(BigDecimal.TEN), eq(1800L), any());
        when(repository.find(10L)).thenReturn(Optional.of(existing(ReservationStatus.RESERVED)));

        OrderApplicationService.CreateOrderResult result = service.createPending(command);

        assertThat(result.state()).isEqualTo("RESERVED");
        assertThat(result.replayed()).isTrue();
        verify(inventory, never()).reserve(any());
    }

    @Test
    void sameInFlightRequestSafelyResumesIdempotentInventoryReservation() {
        doThrow(new DuplicateKeyException("duplicate order"))
                .when(repository).insertPending(eq(10L), eq(20L), eq(BigDecimal.TEN), eq(1800L), any());
        when(repository.find(10L)).thenReturn(Optional.of(existing(ReservationStatus.RESERVING)));
        when(inventory.reserve(any())).thenReturn(InventoryReserveResponse.reserved());
        when(repository.updateReservation(10L, ReservationStatus.RESERVED, null)).thenReturn(true);

        OrderApplicationService.CreateOrderResult result = service.createPending(command);

        assertThat(result.state()).isEqualTo("RESERVED");
        assertThat(result.replayed()).isTrue();
        verify(repository).updateReservation(10L, ReservationStatus.RESERVED, null);
    }

    @Test
    void reusedOrderIdWithDifferentPayloadReturnsConflict() {
        doThrow(new DuplicateKeyException("duplicate order"))
                .when(repository).insertPending(eq(10L), eq(20L), eq(BigDecimal.TEN), eq(1800L), any());
        OrderRecord different = new OrderRecord(10L, 20L, BigDecimal.TEN, 1800L,
                OrderStatus.PENDING_PAYMENT, ReservationStatus.RESERVED, null,
                null, null, null,
                List.of(new OrderItemRecord(1001L, 1L, 2, new BigDecimal("6.00"))));
        when(repository.find(10L)).thenReturn(Optional.of(different));

        OrderApplicationService.CreateOrderResult result = service.createPending(command);

        assertThat(result.state()).isEqualTo("CONFLICT");
        verify(inventory, never()).reserve(any());
    }

    @Test
    void priceWithMoreThanTwoDecimalPlacesIsRejectedBeforePersistence() {
        OrderApplicationService.CreateOrderCommand invalid =
                new OrderApplicationService.CreateOrderCommand(10L, 20L, BigDecimal.TEN,
                        1800L,
                        List.of(new OrderApplicationService.OrderItemCommand(
                                1001L, 1L, 2, new BigDecimal("5.001"))));

        assertThatThrownBy(() -> service.createPending(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DECIMAL(12,2)");
        verify(repository, never()).insertPending(anyLong(), anyLong(), any(), anyLong(), any());
    }

    @Test
    void ownerCancelSchedulesReleaseAndRemainsIdempotent() {
        when(repository.find(10L)).thenReturn(Optional.of(existing(ReservationStatus.RESERVED)),
                Optional.of(new OrderRecord(10L, 20L, BigDecimal.TEN, 1800L,
                        OrderStatus.CANCELED, ReservationStatus.COMPENSATED, null,
                        null, null, null, List.of())));
        when(repository.markUserCanceledForCompensation(10L, 20L)).thenReturn(true);
        when(inventory.release(any())).thenReturn(InventoryReleaseResponse.released());

        assertThat(service.cancelOwned(10L, 20L).state()).isEqualTo("CANCELED");
        assertThat(service.cancelOwned(10L, 20L).state()).isEqualTo("CANCELED");
        verify(inventory).release(any());
        verify(repository).markCompensatedIfPending(10L, "background compensation released inventory");
    }

    @Test
    void strangerCannotCancelAndPaidOrderCannotBeCanceled() {
        when(repository.find(10L)).thenReturn(Optional.of(existing(ReservationStatus.RESERVED)));
        assertThat(service.cancelOwned(10L, 21L).state()).isEqualTo("NOT_FOUND");

        OrderRecord paid = new OrderRecord(10L, 20L, BigDecimal.TEN, 1800L,
                OrderStatus.PAID, ReservationStatus.RESERVED, null,
                "trade-1", null, null, List.of());
        when(repository.find(10L)).thenReturn(Optional.of(paid));
        assertThat(service.cancelOwned(10L, 20L).state()).isEqualTo("CONFLICT");
        verify(repository, never()).markUserCanceledForCompensation(anyLong(), anyLong());
    }

    @Test
    void reserveFinishingAfterCancelNeverReportsReserved() {
        when(inventory.reserve(any())).thenReturn(InventoryReserveResponse.reserved());
        when(repository.updateReservation(10L, ReservationStatus.RESERVED, null)).thenReturn(false);
        when(repository.find(10L)).thenReturn(Optional.of(new OrderRecord(10L, 20L,
                BigDecimal.TEN, 1800L, OrderStatus.CANCELED,
                ReservationStatus.PENDING_COMPENSATION, null, null, null, null, List.of())));
        when(inventory.release(any())).thenReturn(InventoryReleaseResponse.released());

        assertThat(service.createPending(command).state()).isEqualTo("PENDING_COMPENSATION");
        verify(repository).markCompensatedIfPending(10L, "background compensation released inventory");
    }

    @Test
    void duplicateSuccessfulReservationNeverReleasesValidOrder() {
        when(inventory.reserve(any())).thenReturn(InventoryReserveResponse.reserved());
        when(repository.updateReservation(10L, ReservationStatus.RESERVED, null)).thenReturn(false);
        when(repository.find(10L)).thenReturn(Optional.of(existing(ReservationStatus.RESERVED)));

        assertThat(service.createPending(command).state()).isEqualTo("RESERVED");
        verify(inventory, never()).release(any());
    }

    private static OrderRecord existing(ReservationStatus reservationStatus) {
        return new OrderRecord(10L, 20L, BigDecimal.TEN, 1800L, OrderStatus.PENDING_PAYMENT,
                reservationStatus, null, null, null,
                null,
                List.of(new OrderItemRecord(1001L, 1L, 2, new BigDecimal("5.00"))));
    }
}
