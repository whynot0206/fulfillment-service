package com.why.fulfillment.order.service;

import com.why.fulfillment.inventory.service.StockReservationItem;
import com.why.fulfillment.order.entity.AsyncOrderCommand;
import com.why.fulfillment.order.entity.Order;

import java.time.Duration;
import java.util.List;

/** Creates the durable command after Redis inventory pre-deduction succeeds. */
public interface AsyncOrderCommandService {

    AsyncOrderCommand enqueue(Order order, List<StockReservationItem> items, Duration timeout);

    AsyncOrderCommand findByOrderId(Long orderId);
}
