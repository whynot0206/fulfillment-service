package com.why.fulfillment.payment.service;

import com.why.fulfillment.api.order.OrderClient;
import com.why.fulfillment.api.order.OrderMarkPaidRequest;
import com.why.fulfillment.api.order.OrderMarkPaidResponse;
import com.why.fulfillment.api.order.OrderPaymentView;
import feign.FeignException;
import org.springframework.stereotype.Service;

@Service
public class PaymentApplicationService {

    private final OrderClient orderClient;

    public PaymentApplicationService(OrderClient orderClient) {
        this.orderClient = orderClient;
    }

    public CallbackResult acceptSuccess(long orderId, String outTradeNo) {
        if (orderId <= 0 || outTradeNo == null || outTradeNo.isBlank()) {
            throw new IllegalArgumentException("positive orderId and outTradeNo are required");
        }
        try {
            OrderMarkPaidResponse response = orderClient.markPaid(
                    new OrderMarkPaidRequest(orderId, outTradeNo));
            if (response == null) {
                return CallbackResult.unknown(orderId, "order service returned an empty response");
            }
            if (response.accepted()) {
                // PAID includes the idempotent replay case: order service is the source of truth.
                return CallbackResult.accepted(orderId);
            }
            return CallbackResult.rejected(orderId,
                    response.error() == null ? "order rejected payment" : response.error());
        } catch (FeignException exception) {
            return CallbackResult.unknown(orderId, "order service is temporarily unavailable");
        } catch (RuntimeException exception) {
            return CallbackResult.unknown(orderId, "order service is temporarily unavailable");
        }
    }

    /** Local demo only. The caller's identity is checked against Order before changing state. */
    public CallbackResult mockSuccess(long orderId, long userId) {
        if (orderId <= 0 || userId <= 0) {
            throw new IllegalArgumentException("positive orderId and userId are required");
        }
        try {
            OrderPaymentView order = orderClient.paymentView(orderId);
            if (order == null || order.userId() != userId) {
                return CallbackResult.notFound(orderId);
            }
            if (!"PENDING_PAYMENT".equals(order.status())) {
                if ("PAID".equals(order.status())) {
                    return acceptSuccess(orderId, "MOCK-" + orderId);
                }
                return CallbackResult.rejected(orderId, "order is no longer payable");
            }
            return acceptSuccess(orderId, "MOCK-" + orderId);
        } catch (FeignException.NotFound notFound) {
            return CallbackResult.notFound(orderId);
        } catch (RuntimeException unavailable) {
            return CallbackResult.unknown(orderId, "order service is temporarily unavailable");
        }
    }

    private static String message(Throwable exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    public record CallbackResult(long orderId, String status, String error) {
        static CallbackResult accepted(long orderId) {
            return new CallbackResult(orderId, "ACCEPTED", null);
        }

        static CallbackResult rejected(long orderId, String error) {
            return new CallbackResult(orderId, "REJECTED", error);
        }

        static CallbackResult unknown(long orderId, String error) {
            return new CallbackResult(orderId, "UNKNOWN", error);
        }

        static CallbackResult notFound(long orderId) {
            return new CallbackResult(orderId, "NOT_FOUND", "order not found");
        }
    }
}
