package com.why.fulfillment.observability;

import com.why.fulfillment.inventory.reconciliation.StockReconciliationReport;
import com.why.fulfillment.inventory.reconciliation.StockReconciliationService;
import com.why.fulfillment.inventory.reconciliation.StockReconciliationState;
import com.why.fulfillment.order.entity.AsyncOrderCommand;
import com.why.fulfillment.order.entity.Order;
import com.why.fulfillment.order.mapper.AsyncOrderCommandMapper;
import com.why.fulfillment.order.mapper.OrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

@Service
public class OperationsDashboardService {

    private final AsyncOrderCommandMapper commandMapper;
    private final OrderMapper orderMapper;
    private final StockReconciliationService reconciliationService;
    private final FulfillmentMetrics metrics;
    private final StockReconciliationState reconciliationState;

    public OperationsDashboardService(AsyncOrderCommandMapper commandMapper,
                                      OrderMapper orderMapper,
                                      StockReconciliationService reconciliationService,
                                      FulfillmentMetrics metrics) {
        this(commandMapper, orderMapper, reconciliationService, metrics,
                new StockReconciliationState());
    }

    @Autowired
    public OperationsDashboardService(AsyncOrderCommandMapper commandMapper,
                                      OrderMapper orderMapper,
                                      StockReconciliationService reconciliationService,
                                      FulfillmentMetrics metrics,
                                      StockReconciliationState reconciliationState) {
        this.commandMapper = commandMapper;
        this.orderMapper = orderMapper;
        this.reconciliationService = reconciliationService;
        this.metrics = metrics;
        this.reconciliationState = reconciliationState;
    }

    public Snapshot snapshot() {
        long pending = commandMapper.countByStatus(AsyncOrderCommand.PENDING);
        long processing = commandMapper.countByStatus(AsyncOrderCommand.PROCESSING);
        long succeeded = commandMapper.countByStatus(AsyncOrderCommand.SUCCEEDED);
        long dead = commandMapper.countByStatus(AsyncOrderCommand.DEAD);
        metrics.updateAsyncCurrent(pending, processing, dead);
        StockReconciliationReport reconciliation = reconciliationState.latest().orElseGet(() -> {
            StockReconciliationReport first = reconciliationService.inspect();
            reconciliationState.update(first);
            return first;
        });
        metrics.updateReconciliationDifferences(reconciliation.differences().size());
        return new Snapshot(LocalDateTime.now(), metrics.snapshot(),
                new CommandCounts(pending, processing, succeeded, dead),
                new OrderCounts(orderMapper.countByStatus(Order.PENDING_PAYMENT),
                        orderMapper.countByStatus(Order.PAID),
                        orderMapper.countByStatus(Order.CANCELED)),
                reconciliation);
    }

    public record Snapshot(LocalDateTime generatedAt,
                           FulfillmentMetrics.Snapshot metrics,
                           CommandCounts commands,
                           OrderCounts orders,
                           StockReconciliationReport reconciliation) { }

    public record CommandCounts(long pending, long processing, long succeeded, long dead) { }

    public record OrderCounts(long pending, long paid, long cancelled) { }
}
