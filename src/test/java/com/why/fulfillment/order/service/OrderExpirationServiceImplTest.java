package com.why.fulfillment.order.service;

import com.why.fulfillment.inventory.service.InventoryReservationService;
import com.why.fulfillment.order.mapper.OrderMapper;
import com.why.fulfillment.order.service.impl.OrderExpirationServiceImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderExpirationServiceImplTest {

    @Test
    void releasesInventoryOnlyWhenPendingOrderWasCanceled() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        InventoryReservationService inventory = mock(InventoryReservationService.class);
        OrderExpirationService service = new OrderExpirationServiceImpl(orderMapper, inventory);
        when(orderMapper.cancelIfPending(100L)).thenReturn(1);

        assertTrue(service.expire(100L));

        verify(inventory).release(100L);
    }

    @Test
    void doesNotReleaseInventoryWhenPaymentWonTheRace() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        InventoryReservationService inventory = mock(InventoryReservationService.class);
        OrderExpirationService service = new OrderExpirationServiceImpl(orderMapper, inventory);
        when(orderMapper.cancelIfPending(100L)).thenReturn(0);

        assertFalse(service.expire(100L));

        verify(inventory, never()).release(100L);
    }
}
