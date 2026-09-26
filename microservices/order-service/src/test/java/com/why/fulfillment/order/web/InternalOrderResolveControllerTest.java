package com.why.fulfillment.order.web;

import com.why.fulfillment.api.order.OrderClient;
import com.why.fulfillment.api.order.OrderCreateRequest;
import com.why.fulfillment.order.service.OrderApplicationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalOrderResolveControllerTest {
    private static final String PATH = "/internal/orders/resolve-create";
    private static final String BODY = """
            {"orderId":10,"userId":20,"totalAmount":10.00,"timeoutSeconds":1800,
             "items":[{"skuId":1001,"spuId":1,"count":2,"price":5.00,"nameSnapshot":"keyboard"}]}
            """;
    private final OrderApplicationService service = mock(OrderApplicationService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new InternalOrderController(service))
            .setControllerAdvice(new OrderApiExceptionHandler())
            .addFilters(new InternalServiceTokenFilter("local-test-token"))
            .build();

    @ParameterizedTest
    @ValueSource(strings = {"NOT_FOUND", "CONFLICT", "RESERVING", "PENDING_COMPENSATION",
            "RESERVED", "PAID", "FAILED", "COMPENSATED", "CANCELED", "CLOSED"})
    void preservesBusinessStateWithoutInvokingTheCreatingMethod(String state) throws Exception {
        when(service.resolveCreateFromCommerce(any())).thenReturn(
                new OrderApplicationService.CreateOrderResult(10L, state, "current facts", true));

        mvc.perform(post(PATH).header("X-Internal-Service-Token", "local-test-token")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(state))
                .andExpect(jsonPath("$.replayed").value(true));

        ArgumentCaptor<OrderCreateRequest> request = ArgumentCaptor.forClass(OrderCreateRequest.class);
        verify(service).resolveCreateFromCommerce(request.capture());
        assertThat(request.getValue().orderId()).isEqualTo(10L);
        assertThat(request.getValue().items().get(0).nameSnapshot()).isEqualTo("keyboard");
        verifyNoMoreInteractions(service);
    }

    @Test
    void internalTokenIsRequiredBeforeResolution() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void invalidSnapshotUsesExistingBadRequestMapping() throws Exception {
        when(service.resolveCreateFromCommerce(any())).thenThrow(new IllegalArgumentException("invalid snapshot"));
        mvc.perform(post(PATH).header("X-Internal-Service-Token", "local-test-token")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void feignUsesTheSameReadOnlyPostRoute() throws Exception {
        PostMapping mapping = OrderClient.class.getMethod("resolveCreate", OrderCreateRequest.class)
                .getAnnotation(PostMapping.class);
        assertThat(mapping.value()).containsExactly(PATH);
    }
}
