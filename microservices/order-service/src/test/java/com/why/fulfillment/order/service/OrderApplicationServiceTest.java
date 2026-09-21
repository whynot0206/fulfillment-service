package com.why.fulfillment.order.service;

import com.why.fulfillment.api.inventory.InventoryClient;
import com.why.fulfillment.api.inventory.InventoryReleaseResponse;
import com.why.fulfillment.api.inventory.InventoryReserveItem;
import com.why.fulfillment.api.inventory.InventoryReserveResponse;
import com.why.fulfillment.order.domain.ReservationStatus;
import com.why.fulfillment.order.repository.OrderRepository;
import feign.FeignException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderApplicationServiceTest {

    private final OrderRepository repository = mock(OrderRepository.class);
    private final InventoryClient inventory = mock(InventoryClient.class);
    private final OrderApplicationService service = new OrderApplicationService(repository, inventory);
    private final OrderApplicationService.CreateOrderCommand command =
            new OrderApplicationService.CreateOrderCommand(10L, 20L, BigDecimal.TEN,
                    List.of(new InventoryReserveItem(1001L, 1L, 2)));

    @Test
    void deterministicInventoryRejectionMarksOrderFailedWithoutRelease() {
        when(inventory.reserve(any())).thenReturn(InventoryReserveResponse.rejected("insufficient stock"));

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
}
