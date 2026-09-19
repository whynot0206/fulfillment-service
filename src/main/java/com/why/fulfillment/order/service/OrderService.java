package com.why.fulfillment.order.service;

import com.why.fulfillment.inventory.service.StockReservationItem;
import com.why.fulfillment.order.entity.Order;

import java.time.Duration;
import java.util.List;

public interface OrderService {

    void createPending(Order order, List<StockReservationItem> items, Duration timeout);

    boolean markPaid(Long orderId, String outTradeNo);
}
