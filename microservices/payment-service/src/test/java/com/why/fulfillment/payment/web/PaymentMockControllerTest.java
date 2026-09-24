package com.why.fulfillment.payment.web;

import com.why.fulfillment.payment.service.PaymentApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentMockControllerTest {
    private final PaymentApplicationService service = mock(PaymentApplicationService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PaymentMockController(service))
            .addFilters(new PaymentMockTokenFilter("test-internal-token"))
            .build();

    @Test
    void directCallerCannotForgeUserIdWithoutInternalToken() throws Exception {
        mvc.perform(post("/api/payments/orders/12/mock-success").header("X-User-Id", "42"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void gatewayStyleRequestCanStartMockPayment() throws Exception {
        when(service.mockSuccess(12L, 42L)).thenReturn(
                new PaymentApplicationService.CallbackResult(12L, "ACCEPTED", null));
        mvc.perform(post("/api/payments/orders/12/mock-success")
                        .header("X-User-Id", "42")
                        .header("X-Internal-Service-Token", "test-internal-token"))
                .andExpect(status().isOk());
    }
}
