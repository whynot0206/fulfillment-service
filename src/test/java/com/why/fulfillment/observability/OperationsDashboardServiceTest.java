package com.why.fulfillment.observability;

import com.why.fulfillment.inventory.reconciliation.StockReconciliationItem;
import com.why.fulfillment.inventory.reconciliation.StockReconciliationReport;
import com.why.fulfillment.inventory.reconciliation.StockReconciliationService;
import com.why.fulfillment.order.entity.AsyncOrderCommand;
import com.why.fulfillment.order.entity.Order;
import com.why.fulfillment.order.mapper.AsyncOrderCommandMapper;
import com.why.fulfillment.order.mapper.OrderMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperationsDashboardServiceTest {

    @Test
    void snapshotCombinesBusinessCountersBacklogAndReconciliation() {
        AsyncOrderCommandMapper commands = mock(AsyncOrderCommandMapper.class);
        OrderMapper orders = mock(OrderMapper.class);
        StockReconciliationService reconciliation = mock(StockReconciliationService.class);
        FulfillmentMetrics metrics = new FulfillmentMetrics(new SimpleMeterRegistry());
        metrics.orderAccepted("mysql");
        metrics.rateLimited("global");

        when(commands.countByStatus(AsyncOrderCommand.PENDING)).thenReturn(2L);
        when(commands.countByStatus(AsyncOrderCommand.PROCESSING)).thenReturn(1L);
        when(commands.countByStatus(AsyncOrderCommand.SUCCEEDED)).thenReturn(8L);
        when(commands.countByStatus(AsyncOrderCommand.DEAD)).thenReturn(1L);
        when(orders.countByStatus(Order.PENDING_PAYMENT)).thenReturn(4L);
        when(orders.countByStatus(Order.PAID)).thenReturn(6L);
        when(orders.countByStatus(Order.CANCELED)).thenReturn(3L);
        when(reconciliation.inspect()).thenReturn(new StockReconciliationReport(
                LocalDateTime.now(), 5,
                List.of(new StockReconciliationItem(1001L, 10, 0, 10, 9L,
                        StockReconciliationItem.Status.DIFFERENT)), List.of()));

        OperationsDashboardService.Snapshot snapshot = new OperationsDashboardService(
                commands, orders, reconciliation, metrics).snapshot();

        assertEquals(2, snapshot.commands().pending());
        assertEquals(8, snapshot.commands().succeeded());
        assertEquals(6, snapshot.orders().paid());
        assertEquals(1, snapshot.metrics().mysqlOrdersAccepted());
        assertEquals(1, snapshot.metrics().globalRateLimitRejections());
        assertEquals(1, snapshot.metrics().reconciliationDifferences());
    }
}
