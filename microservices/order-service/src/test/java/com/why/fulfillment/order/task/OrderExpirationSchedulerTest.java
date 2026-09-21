package com.why.fulfillment.order.task;

import com.why.fulfillment.order.repository.OrderRepository;
import com.why.fulfillment.order.service.OrderApplicationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderExpirationSchedulerTest {

    private final OrderRepository repository = mock(OrderRepository.class);
    private final OrderApplicationService service = mock(OrderApplicationService.class);
    private final OrderExpirationScheduler scheduler = new OrderExpirationScheduler(repository, service);

    @Test
    void claimedExpiredOrderImmediatelyAttemptsInventoryRelease() {
        when(repository.findExpiredReservedOrderIds(50)).thenReturn(List.of(10L));
        when(repository.markExpiredForCompensation(10L)).thenReturn(true);

        scheduler.closeExpiredOrders();

        verify(service).retryPendingCompensation(10L);
    }

    @Test
    void losingConditionalCloseRaceDoesNotReleaseInventory() {
        when(repository.findExpiredReservedOrderIds(50)).thenReturn(List.of(10L));
        when(repository.markExpiredForCompensation(10L)).thenReturn(false);

        scheduler.closeExpiredOrders();

        verify(service, never()).retryPendingCompensation(10L);
    }
}
