package com.why.fulfillment.order.web;

import com.why.fulfillment.order.domain.OrderRecord;
import com.why.fulfillment.order.service.OrderApplicationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * Public order API.
 *
 * <p><b>Where the identity on the read paths comes from.</b> {@code X-User-Id} is injected by
 * the gateway after it verified the caller's JWT; the gateway strips any client-supplied copy
 * of that header first, so a browser cannot choose its own id. Order service does not parse
 * JWTs itself — it has no user table and no signing key, and giving it one would mean two
 * places to rotate the secret.</p>
 *
 * <p><b>The gap this does not close.</b> Port 18081 is still reachable directly, and anything
 * that can reach it can send whatever {@code X-User-Id} it likes. The header is only
 * trustworthy because the gateway is the only reachable door — that is a deployment property,
 * not something this class enforces. Closing it properly means binding 18081 to an internal
 * interface, or extending the internal-token requirement to {@code /api/**} as well.</p>
 *
 * <p>{@code POST /api/orders} deliberately keeps taking {@code userId} in the body and is left
 * exactly as it was: it is the entry point the JMeter plan measures, and changing its request
 * shape would invalidate the comparison against earlier runs.</p>
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final String USER_ID_HEADER = "X-User-Id";

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
                        request.orderId(), request.userId(), request.totalAmount(), request.timeoutSeconds(),
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

    /** The caller's own orders, newest first. */
    @GetMapping
    public ResponseEntity<OrderApplicationService.OrderPage> list(
            @RequestHeader(USER_ID_HEADER) long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(service.findForUser(userId, page, size));
    }

    /**
     * One order, if it belongs to the caller.
     *
     * <p>Someone else's order and a non-existent order both answer 404. Answering 403 for the
     * first would confirm that the id exists, which is all an attacker walking the id space
     * needs.</p>
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderRecord> find(@RequestHeader(USER_ID_HEADER) long userId,
                                            @PathVariable long orderId) {
        return service.findOwned(orderId, userId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record CreateOrderRequest(long orderId, long userId, BigDecimal totalAmount, Long timeoutSeconds,
                                     List<CreateOrderItemRequest> items) {
    }

    public record CreateOrderItemRequest(Long skuId, Long spuId, Integer count, BigDecimal price) {
    }
}
