package com.why.fulfillment.order.service.impl;

import com.why.fulfillment.inventory.service.InventoryReservationService;
import com.why.fulfillment.inventory.service.StockReservationItem;
import com.why.fulfillment.order.entity.Order;
import com.why.fulfillment.order.event.OrderCreatedEvent;
import com.why.fulfillment.order.mapper.OrderMapper;
import com.why.fulfillment.order.mapper.OrderOutboxEventMapper;
import com.why.fulfillment.order.entity.OrderOutboxEvent;
import com.why.fulfillment.order.service.OrderService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

@Service
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    private final InventoryReservationService inventoryReservationService;
    private final ApplicationEventPublisher eventPublisher;
    private final OrderOutboxEventMapper outboxEventMapper;

    public OrderServiceImpl(OrderMapper orderMapper,
                            InventoryReservationService inventoryReservationService,
                            ApplicationEventPublisher eventPublisher,
                            OrderOutboxEventMapper outboxEventMapper) {
        this.orderMapper = orderMapper;
        this.inventoryReservationService = inventoryReservationService;
        this.eventPublisher = eventPublisher;
        this.outboxEventMapper = outboxEventMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createPending(Order order, List<StockReservationItem> items, Duration timeout) {
        if (order == null || order.getOrderId() == null || timeout == null || timeout.isNegative()
                || timeout.isZero()) {
            throw new IllegalArgumentException("invalid pending order");
        }
        order.setStatus(Order.PENDING_PAYMENT);
        if (orderMapper.insert(order) != 1) {
            throw new IllegalStateException("failed to create order " + order.getOrderId());
        }
        inventoryReservationService.reserve(order.getOrderId(), items);
        eventPublisher.publishEvent(new OrderCreatedEvent(order.getOrderId(), timeout));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markPaid(Long orderId, String outTradeNo) {
        if (orderId == null || outTradeNo == null || outTradeNo.isBlank()) {
            throw new IllegalArgumentException("orderId and outTradeNo are required");
        }
        try {
            if (orderMapper.markPaidIfPending(orderId, outTradeNo) == 1) {
                outboxEventMapper.insert(OrderOutboxEvent.paymentConfirmed(orderId, outTradeNo));
                return true;
            }
        } catch (DuplicateKeyException exception) {
            // uk_out_trade_no 已经把该交易号归属给另一订单，明确返回业务冲突语义。
            throw new IllegalStateException("outTradeNo already belongs to another order", exception);
        }

        // markPaidIfPending 使用当前读抢占待支付订单；并发输家会先等待赢家提交，再以 0 行更新返回。
        // 这里的 selectById 必须保持为本事务里的第一次一致性读，不能在它上面新增任何 SELECT：
        // MySQL 默认 RR 隔离级别会在第一次一致性读时建立快照，只有这样才能读到赢家已经提交的 PAID。
        // 只有状态和外部交易号都完全匹配，才能把重复回调判为幂等成功；其他状态都必须返回 false。
        Order existing = orderMapper.selectById(orderId);
        return existing != null
                && Order.PAID == existing.getStatus()
                && outTradeNo.equals(existing.getOutTradeNo());
    }
}
