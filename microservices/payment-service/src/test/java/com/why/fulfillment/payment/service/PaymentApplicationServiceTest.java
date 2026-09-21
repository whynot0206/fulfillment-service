package com.why.fulfillment.payment.service;

import com.why.fulfillment.api.order.OrderClient;
import com.why.fulfillment.api.order.OrderMarkPaidResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
}
