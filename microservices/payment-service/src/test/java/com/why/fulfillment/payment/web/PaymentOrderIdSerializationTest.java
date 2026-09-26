package com.why.fulfillment.payment.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.why.fulfillment.payment.service.PaymentApplicationService;
import com.why.fulfillment.payment.service.PaymentCallbackSignatureVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentOrderIdSerializationTest {
    private static final long ORDER_ID = 9_007_199_254_740_993L;
    private static final String ORDER_ID_TEXT = "9007199254740993";
    private final PaymentApplicationService service = mock(PaymentApplicationService.class);
    private final PaymentCallbackSignatureVerifier verifier = mock(PaymentCallbackSignatureVerifier.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new PaymentMockController(service), new PaymentCallbackController(service, verifier)).build();

    @Test
    void mockPaymentKeepsExactBrowserOrderIdInTheRequestAndResponse() throws Exception {
        when(service.mockSuccess(ORDER_ID, 42L)).thenReturn(
                new PaymentApplicationService.CallbackResult(ORDER_ID, "ACCEPTED", null));

        String body = mvc.perform(post("/api/payments/orders/{orderId}/mock-success", ORDER_ID_TEXT)
                        .header("X-User-Id", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").isString())
                .andExpect(jsonPath("$.orderId").value(ORDER_ID_TEXT))
                .andReturn().getResponse().getContentAsString();

        assertThat(new ObjectMapper().readValue(body, PaymentApplicationService.CallbackResult.class).orderId())
                .isEqualTo(ORDER_ID);
        verify(service).mockSuccess(ORDER_ID, 42L);
    }

    @Test
    void signedCallbackStillAcceptsNumericJavaOrderIdsAndReturnsExactText() throws Exception {
        when(verifier.verify(ORDER_ID, "test-trade", 123L, "test-signature")).thenReturn(true);
        when(service.acceptSuccess(ORDER_ID, "test-trade")).thenReturn(
                new PaymentApplicationService.CallbackResult(ORDER_ID, "ACCEPTED", null));

        mvc.perform(post("/api/payments/callbacks/success")
                        .header("X-Payment-Timestamp", "123")
                        .header("X-Payment-Signature", "test-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":" + ORDER_ID_TEXT + ",\"outTradeNo\":\"test-trade\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").isString())
                .andExpect(jsonPath("$.orderId").value(ORDER_ID_TEXT));

        verify(service).acceptSuccess(ORDER_ID, "test-trade");
    }
}
