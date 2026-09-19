package com.why.fulfillment.order.service;

import com.why.fulfillment.inventory.service.InventoryReservationService;
import com.why.fulfillment.order.entity.Order;
import com.why.fulfillment.order.entity.OrderOutboxEvent;
import com.why.fulfillment.order.mapper.OrderMapper;
import com.why.fulfillment.order.mapper.OrderOutboxEventMapper;
import com.why.fulfillment.order.service.impl.OrderServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceImplTest {

    @Test
    void paymentCallbackWritesOutboxInSameServiceTransaction() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderOutboxEventMapper outbox = mock(OrderOutboxEventMapper.class);
        InventoryReservationService inventory = mock(InventoryReservationService.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        when(orderMapper.markPaidIfPending(100L, "trade-1")).thenReturn(1);
        when(outbox.insert(any(OrderOutboxEvent.class))).thenReturn(1);

        OrderService service = new OrderServiceImpl(orderMapper, inventory, events, outbox);

        assertTrue(service.markPaid(100L, "trade-1"));

        verify(outbox).insert(any(OrderOutboxEvent.class));
        verify(inventory, never()).confirm(100L);
    }

    @Test
    void repeatedSameTradeCallbackIsIdempotent() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderOutboxEventMapper outbox = mock(OrderOutboxEventMapper.class);
        InventoryReservationService inventory = mock(InventoryReservationService.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        when(orderMapper.markPaidIfPending(100L, "trade-1")).thenReturn(0);
        Order existing = new Order();
        existing.setOrderId(100L);
        existing.setStatus(Order.PAID);
        existing.setOutTradeNo("trade-1");
        when(orderMapper.selectById(100L)).thenReturn(existing);

        OrderService service = new OrderServiceImpl(orderMapper, inventory, events, outbox);

        assertTrue(service.markPaid(100L, "trade-1"));

        verify(outbox, never()).insert(any(OrderOutboxEvent.class));
    }

    @Test
    void tradeNumberOwnedByAnotherOrderReturnsExplicitConflict() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderOutboxEventMapper outbox = mock(OrderOutboxEventMapper.class);
        InventoryReservationService inventory = mock(InventoryReservationService.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        when(orderMapper.markPaidIfPending(100L, "trade-owned"))
                .thenThrow(new DuplicateKeyException("uk_out_trade_no"));

        OrderService service = new OrderServiceImpl(orderMapper, inventory, events, outbox);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.markPaid(100L, "trade-owned"));
        assertEquals("outTradeNo already belongs to another order", exception.getMessage());
        verify(outbox, never()).insert(any(OrderOutboxEvent.class));
    }
}
