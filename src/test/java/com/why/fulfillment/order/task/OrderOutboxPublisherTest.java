package com.why.fulfillment.order.task;

import com.why.fulfillment.inventory.service.InventoryReservationService;
import com.why.fulfillment.order.entity.OrderOutboxEvent;
import com.why.fulfillment.order.mapper.OrderOutboxEventMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OrderOutboxPublisherTest {

    @Test
    void successfulPublishConfirmsInventoryAndMarksEventSent() {
        OrderOutboxEventMapper mapper = mock(OrderOutboxEventMapper.class);
        InventoryReservationService inventory = mock(InventoryReservationService.class);
        OrderOutboxEvent event = event(11L, 0);
        when(mapper.listReady(100)).thenReturn(List.of(event));
        when(mapper.claim(11L)).thenReturn(1);

        new OrderOutboxPublisher(mapper, inventory).publishReadyEvents();

        verify(inventory).confirm(99L);
        verify(mapper).markSent(11L);
        verify(mapper, never()).reschedule(any(), anyInt(), any(), any());
    }

    @Test
    void failedPublishSchedulesExponentialRetryAndKeepsEventPending() {
        OrderOutboxEventMapper mapper = mock(OrderOutboxEventMapper.class);
        InventoryReservationService inventory = mock(InventoryReservationService.class);
        OrderOutboxEvent event = event(12L, 2);
        when(mapper.listReady(100)).thenReturn(List.of(event));
        when(mapper.claim(12L)).thenReturn(1);
        doThrow(new IllegalStateException("inventory unavailable"))
                .when(inventory).confirm(99L);

        new OrderOutboxPublisher(mapper, inventory).publishReadyEvents();

        verify(mapper).reschedule(eq(12L), eq(3), any(), eq("inventory unavailable"));
        verify(mapper, never()).markSent(12L);
    }

    @Test
    void eventNotClaimedByThisWorkerIsSkipped() {
        OrderOutboxEventMapper mapper = mock(OrderOutboxEventMapper.class);
        InventoryReservationService inventory = mock(InventoryReservationService.class);
        OrderOutboxEvent event = event(13L, 0);
        when(mapper.listReady(100)).thenReturn(List.of(event));
        when(mapper.claim(13L)).thenReturn(0);

        new OrderOutboxPublisher(mapper, inventory).publishReadyEvents();

        verifyNoInteractions(inventory);
        verify(mapper, never()).markSent(13L);
        verify(mapper, never()).reschedule(any(), anyInt(), any(), any());
    }

    @Test
    void invalidPayloadIsRescheduledEvenWhenRetryCountIsNull() {
        OrderOutboxEventMapper mapper = mock(OrderOutboxEventMapper.class);
        InventoryReservationService inventory = mock(InventoryReservationService.class);
        OrderOutboxEvent event = event(14L, 0);
        event.setPayload("malformed");
        event.setRetryCount(null);
        when(mapper.listReady(100)).thenReturn(List.of(event));
        when(mapper.claim(14L)).thenReturn(1);

        new OrderOutboxPublisher(mapper, inventory).publishReadyEvents();

        verify(mapper).reschedule(eq(14L), eq(1), any(), eq("invalid payment event payload"));
        verifyNoInteractions(inventory);
    }

    private static OrderOutboxEvent event(long eventId, int retryCount) {
        OrderOutboxEvent event = OrderOutboxEvent.paymentConfirmed(99L, "trade-99");
        event.setEventId(eventId);
        event.setRetryCount(retryCount);
        return event;
    }
}
