package com.why.fulfillment.order.web;

import com.why.fulfillment.api.order.OrderCreateRequest;
import com.why.fulfillment.api.order.OrderCreateResponse;
import com.why.fulfillment.api.order.OrderMarkPaidRequest;
import com.why.fulfillment.api.order.OrderMarkPaidResponse;
import com.why.fulfillment.api.order.OrderPaymentView;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import com.why.fulfillment.order.service.OrderApplicationService;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service order endpoints.
 *
 * <p>Everything under {@code /internal} is guarded by {@link InternalServiceTokenFilter} and is
 * not routed by the gateway, so these endpoints trust the {@code userId} in the body. Reaching
 * them requires the shared internal token.</p>
 */
@RestController
@RequestMapping("/internal/orders")
public class InternalOrderController {

    private final OrderApplicationService service;

    public InternalOrderController(OrderApplicationService service) {
        this.service = service;
    }

    @PostMapping("/mark-paid")
    public OrderMarkPaidResponse markPaid(@RequestBody OrderMarkPaidRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        boolean accepted = service.markPaid(request.orderId(), request.outTradeNo());
        return new OrderMarkPaidResponse(accepted, accepted ? "PAID" : "REJECTED",
                accepted ? null : "order is not pending or trade number does not match");
    }

    @GetMapping("/{orderId}/payment-view")
    public OrderPaymentView paymentView(@PathVariable long orderId) {
        var order = service.find(orderId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found"));
        return new OrderPaymentView(order.orderId(), order.userId(), order.totalAmount(),
                order.status().name());
    }

    /**
     * Creates an order from Commerce checkout.
     *
     * <p>Always 200 with a state in the body, never a status-code-encoded outcome. The caller
     * has to branch on the state anyway — {@code PENDING_COMPENSATION} is neither success nor
     * failure — and a Feign client that throws on 4xx/5xx would force Commerce to reconstruct
     * that state from an exception.</p>
     */
    @PostMapping
    public OrderCreateResponse create(@RequestBody OrderCreateRequest request) {
        OrderApplicationService.CreateOrderResult result = service.createFromCommerce(request);
        return new OrderCreateResponse(result.orderId(), result.state(), result.message(),
                result.replayed());
    }

    /** Read-only reconciliation after Commerce's automatic creation window has closed. */
    @PostMapping("/resolve-create")
    public OrderCreateResponse resolveCreate(@RequestBody OrderCreateRequest request) {
        OrderApplicationService.CreateOrderResult result = service.resolveCreateFromCommerce(request);
        return new OrderCreateResponse(result.orderId(), result.state(), result.message(),
                result.replayed());
    }
}
