package com.why.fulfillment.web;

import com.why.fulfillment.inventory.service.StockReservationItem;
import com.why.fulfillment.order.entity.Order;
import com.why.fulfillment.order.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Long>> create(@RequestBody CreateOrderRequest request) {
        if (request == null || request.orderId() == null || request.userId() == null
                || request.totalAmount() == null || request.totalAmount().signum() < 0
                || request.items() == null || request.items().isEmpty()) {
            throw new IllegalArgumentException("orderId, userId, totalAmount and items are required");
        }
        if (request.orderId() <= 0 || request.userId() <= 0) {
            throw new IllegalArgumentException("orderId and userId must be positive");
        }
        long timeoutSeconds = request.timeoutSeconds() == null ? 1800 : request.timeoutSeconds();
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be positive");
        }

        for (OrderItemRequest item : request.items()) {
            if (item == null || item.skuId() == null || item.spuId() == null || item.count() <= 0) {
                throw new IllegalArgumentException("each item must contain positive skuId, spuId and count");
            }
        }

        Order order = new Order();
        order.setOrderId(request.orderId());
        order.setUserId(request.userId());
        order.setTotalAmount(request.totalAmount());
        List<StockReservationItem> items = request.items().stream()
                .map(item -> new StockReservationItem(item.skuId(), item.spuId(), item.count()))
                .toList();
        orderService.createPending(order, items, Duration.ofSeconds(timeoutSeconds));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(order.getOrderId()));
    }

    public record CreateOrderRequest(Long orderId, Long userId, BigDecimal totalAmount,
                                     Long timeoutSeconds, List<OrderItemRequest> items) {
    }

    public record OrderItemRequest(Long skuId, Long spuId, int count) {
    }
}
