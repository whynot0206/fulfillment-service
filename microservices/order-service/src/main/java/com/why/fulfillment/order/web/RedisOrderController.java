package com.why.fulfillment.order.web;

import com.why.fulfillment.order.service.RedisOrderApplicationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/orders/redis")
public class RedisOrderController {

    private final RedisOrderApplicationService service;

    public RedisOrderController(RedisOrderApplicationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<RedisOrderApplicationService.RedisOrderResult> create(
            @RequestBody(required = false) RedisOrderRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        RedisOrderApplicationService.RedisOrderResult result = service.accept(
                new RedisOrderApplicationService.CreateRedisOrderCommand(
                        request.orderId(), request.userId(), request.totalAmount(), request.timeoutSeconds(),
                        request.items() == null ? null : request.items().stream()
                                .map(item -> new RedisOrderApplicationService.RedisOrderItemCommand(
                                        item.skuId(), item.spuId(), item.count(), item.price())).toList()));
        HttpStatus status = switch (result.state()) {
            case "CONFLICT", "REJECTED", "CANCELED", "DEAD" -> HttpStatus.CONFLICT;
            default -> HttpStatus.ACCEPTED;
        };
        return ResponseEntity.status(status).body(result);
    }

    public record RedisOrderRequest(long orderId, long userId, BigDecimal totalAmount, Long timeoutSeconds,
                                    List<RedisOrderItem> items) { }

    public record RedisOrderItem(Long skuId, Long spuId, Integer count, BigDecimal price) { }
}
