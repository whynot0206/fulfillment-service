package com.why.fulfillment.web;

import com.why.fulfillment.inventory.service.RedisInventoryService;
import com.why.fulfillment.inventory.service.StockReservationItem;
import com.why.fulfillment.order.entity.AsyncOrderCommand;
import com.why.fulfillment.order.entity.Order;
import com.why.fulfillment.order.service.AsyncOrderCommandService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/** Redis 快速路径：同步完成原子预扣，订单与 MySQL 库存由持久化命令异步提交。 */
@RestController
@RequestMapping("/api/orders/redis")
public class RedisOrderController {

    private final RedisInventoryService redisInventoryService;
    private final AsyncOrderCommandService commandService;

    public RedisOrderController(RedisInventoryService redisInventoryService,
                                AsyncOrderCommandService commandService) {
        this.redisInventoryService = redisInventoryService;
        this.commandService = commandService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AcceptedOrder>> create(@RequestBody RedisOrderRequest request) {
        validate(request);
        Duration timeout = Duration.ofSeconds(request.timeoutSeconds() == null
                ? 1800 : request.timeoutSeconds());
        List<StockReservationItem> items = request.items().stream()
                .map(item -> new StockReservationItem(item.skuId(), item.spuId(), item.count()))
                .toList();
        Order order = order(request);

        // 已存在的命令先做内容校验并直接返回，避免 Redis 幂等标记过期后重复预扣。
        AsyncOrderCommand existing = commandService.findByOrderId(request.orderId());
        if (existing != null) {
            AsyncOrderCommand command = commandService.enqueue(order, items, timeout);
            return accepted(command);
        }

        if (!redisInventoryService.preDeduct(request.orderId(), items)) {
            throw new IllegalStateException("Redis stock reservation was rejected");
        }
        try {
            return accepted(commandService.enqueue(order, items, timeout));
        } catch (RuntimeException exception) {
            // 数据库命令没有可靠落库时，立刻用带 orderId 幂等标记的 Lua 回补库存。
            redisInventoryService.compensate(request.orderId(), items);
            throw exception;
        }
    }

    private ResponseEntity<ApiResponse<AcceptedOrder>> accepted(AsyncOrderCommand command) {
        AcceptedOrder body = new AcceptedOrder(command.getOrderId(), command.getCommandId(),
                command.getStatus());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(body));
    }

    private Order order(RedisOrderRequest request) {
        Order order = new Order();
        order.setOrderId(request.orderId());
        order.setUserId(request.userId());
        order.setTotalAmount(request.totalAmount());
        return order;
    }

    private void validate(RedisOrderRequest request) {
        if (request == null || request.orderId() == null || request.orderId() <= 0
                || request.userId() == null || request.userId() <= 0
                || request.totalAmount() == null || request.totalAmount().signum() < 0
                || request.items() == null || request.items().isEmpty()) {
            throw new IllegalArgumentException("valid orderId, userId, totalAmount and items are required");
        }
        if (request.timeoutSeconds() != null && request.timeoutSeconds() <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be positive");
        }
        for (RedisOrderItem item : request.items()) {
            if (item == null || item.skuId() == null || item.skuId() <= 0
                    || item.spuId() == null || item.spuId() <= 0 || item.count() <= 0) {
                throw new IllegalArgumentException("each item must contain positive skuId, spuId and count");
            }
        }
    }

    public record RedisOrderRequest(Long orderId, Long userId, BigDecimal totalAmount,
                                    Long timeoutSeconds, List<RedisOrderItem> items) {
    }

    public record RedisOrderItem(Long skuId, Long spuId, int count) {
    }

    public record AcceptedOrder(Long orderId, Long commandId, Integer commandStatus) {
    }
}
