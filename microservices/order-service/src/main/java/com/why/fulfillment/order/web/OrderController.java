package com.why.fulfillment.order.web;

import com.why.fulfillment.order.domain.OrderRecord;
import com.why.fulfillment.order.service.OrderApplicationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderApplicationService service;

    public OrderController(OrderApplicationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<OrderApplicationService.CreateOrderResult> create(@RequestBody CreateOrderRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        OrderApplicationService.CreateOrderResult result = service.createPending(
                new OrderApplicationService.CreateOrderCommand(
                        request.orderId(), request.userId(), request.totalAmount(),
                        request.items() == null ? null : request.items().stream()
                                .map(item -> new OrderApplicationService.OrderItemCommand(
                                        item.skuId(), item.spuId(), item.count(), item.price()))
                                .toList()));
        HttpStatus status = switch (result.state()) {
            case "RESERVED" -> result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
            case "FAILED", "CONFLICT" -> HttpStatus.CONFLICT;
            default -> HttpStatus.ACCEPTED;
        };
        return ResponseEntity.status(status).body(result);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderRecord> find(@PathVariable long orderId) {
        return service.find(orderId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record CreateOrderRequest(long orderId, long userId, BigDecimal totalAmount,
                                     List<CreateOrderItemRequest> items) {
    }

    public record CreateOrderItemRequest(Long skuId, Long spuId, Integer count, BigDecimal price) {
    }
}
