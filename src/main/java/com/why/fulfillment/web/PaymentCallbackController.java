package com.why.fulfillment.web;

import com.why.fulfillment.payment.service.PaymentCallbackService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentCallbackController {

    private final PaymentCallbackService callbackService;

    public PaymentCallbackController(PaymentCallbackService callbackService) {
        this.callbackService = callbackService;
    }

    @PostMapping("/callbacks/success")
    public ApiResponse<Boolean> success(@RequestBody PaymentSuccessRequest request) {
        if (request == null || request.orderId() == null || request.orderId() <= 0
                || request.outTradeNo() == null || request.outTradeNo().isBlank()) {
            throw new IllegalArgumentException("positive orderId and outTradeNo are required");
        }
        return ApiResponse.ok(callbackService.acceptSuccess(request.orderId(), request.outTradeNo()));
    }

    public record PaymentSuccessRequest(Long orderId, String outTradeNo) {
    }
}
