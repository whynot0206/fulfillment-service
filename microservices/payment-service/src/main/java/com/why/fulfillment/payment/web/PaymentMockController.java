package com.why.fulfillment.payment.web;

import com.why.fulfillment.payment.service.PaymentApplicationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicitly named simulation; it never asks the browser for a payment signature. */
@RestController
@RequestMapping("/api/payments/orders")
public class PaymentMockController {
    private final PaymentApplicationService service;

    public PaymentMockController(PaymentApplicationService service) {
        this.service = service;
    }

    @PostMapping("/{orderId}/mock-success")
    public ResponseEntity<PaymentApplicationService.CallbackResult> success(
            @PathVariable long orderId, @RequestHeader("X-User-Id") long userId) {
        var result = service.mockSuccess(orderId, userId);
        HttpStatus status = switch (result.status()) {
            case "ACCEPTED" -> HttpStatus.OK;
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "REJECTED" -> HttpStatus.CONFLICT;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).body(result);
    }
}
