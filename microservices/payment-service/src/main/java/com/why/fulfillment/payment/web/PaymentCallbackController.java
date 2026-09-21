package com.why.fulfillment.payment.web;

import com.why.fulfillment.payment.service.PaymentApplicationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentCallbackController {

    private final PaymentApplicationService service;
    private final com.why.fulfillment.payment.service.PaymentCallbackSignatureVerifier signatureVerifier;

    public PaymentCallbackController(PaymentApplicationService service,
            com.why.fulfillment.payment.service.PaymentCallbackSignatureVerifier signatureVerifier) {
        this.service = service;
        this.signatureVerifier = signatureVerifier;
    }

    @PostMapping("/callbacks/success")
    public ResponseEntity<PaymentApplicationService.CallbackResult> success(
            @RequestBody PaymentSuccessRequest request,
            @RequestHeader("X-Payment-Timestamp") long timestamp,
            @RequestHeader("X-Payment-Signature") String signature) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (!signatureVerifier.verify(request.orderId(), request.outTradeNo(), timestamp, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        PaymentApplicationService.CallbackResult result = service.acceptSuccess(
                request.orderId(), request.outTradeNo());
        HttpStatus status = switch (result.status()) {
            case "ACCEPTED" -> HttpStatus.OK;
            case "REJECTED" -> HttpStatus.CONFLICT;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).body(result);
    }

    public record PaymentSuccessRequest(long orderId, String outTradeNo) {
    }
}
