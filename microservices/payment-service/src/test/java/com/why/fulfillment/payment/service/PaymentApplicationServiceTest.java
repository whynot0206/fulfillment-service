package com.why.fulfillment.payment.service;

import com.why.fulfillment.api.order.OrderClient;
import com.why.fulfillment.api.order.OrderMarkPaidResponse;
import com.why.fulfillment.api.order.OrderPaymentView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class PaymentApplicationServiceTest {

    private final OrderClient orderClient = mock(OrderClient.class);
    private final PaymentApplicationService service = new PaymentApplicationService(orderClient);

    @Test
    void forwardsSuccessAndAcceptsIdempotentOrderResponse() {
        when(orderClient.markPaid(any())).thenReturn(new OrderMarkPaidResponse(true, "PAID", null));

        PaymentApplicationService.CallbackResult result = service.acceptSuccess(12L, "trade-12");

        assertThat(result.status()).isEqualTo("ACCEPTED");
        verify(orderClient).markPaid(any());
    }

    @Test
    void reportsUnknownWhenOrderServiceCannotBeReached() {
        when(orderClient.markPaid(any())).thenThrow(new IllegalStateException("connection reset"));

        PaymentApplicationService.CallbackResult result = service.acceptSuccess(12L, "trade-12");

        assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(result.error()).isEqualTo("order service is temporarily unavailable");
    }

    @Test
    void mockPaymentRequiresOrderOwnership() {
        when(orderClient.paymentView(12L)).thenReturn(
                new OrderPaymentView(12L, 42L, new BigDecimal("19.90"), "PENDING_PAYMENT"));

        assertThat(service.mockSuccess(12L, 43L).status()).isEqualTo("NOT_FOUND");
        verify(orderClient, never()).markPaid(any());
    }

    @Test
    void mockPaymentUsesStableTradeNumberForRetries() {
        when(orderClient.paymentView(12L)).thenReturn(
                new OrderPaymentView(12L, 42L, new BigDecimal("19.90"), "PENDING_PAYMENT"));
        when(orderClient.markPaid(any())).thenReturn(new OrderMarkPaidResponse(true, "PAID", null));

        assertThat(service.mockSuccess(12L, 42L).status()).isEqualTo("ACCEPTED");
        verify(orderClient).markPaid(new com.why.fulfillment.api.order.OrderMarkPaidRequest(12L, "MOCK-12"));
    }
}
