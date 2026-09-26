package com.why.fulfillment.order.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.why.fulfillment.api.order.OrderCreateResponse;
import com.why.fulfillment.order.domain.OrderItemRecord;
import com.why.fulfillment.order.domain.OrderRecord;
import com.why.fulfillment.order.domain.OrderStatus;
import com.why.fulfillment.order.domain.ReservationStatus;
import com.why.fulfillment.order.service.OrderApplicationService;
import com.why.fulfillment.order.service.RedisOrderApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderIdSerializationTest {
    private static final long ORDER_ID = 9_007_199_254_740_993L;
    private static final String ORDER_ID_TEXT = "9007199254740993";
    private static final long USER_ID = 42L;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final OrderApplicationService service = mock(OrderApplicationService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new OrderController(service)).build();

    @Test
    void detailPreservesUnsafeOrderIdAcrossPathBindingAndResponse() throws Exception {
        when(service.findOwned(ORDER_ID, USER_ID)).thenReturn(Optional.of(order()));

        String body = mvc.perform(get("/api/orders/{orderId}", ORDER_ID_TEXT)
                        .header("X-User-Id", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").isString())
                .andExpect(jsonPath("$.orderId").value(ORDER_ID_TEXT))
                .andExpect(jsonPath("$.userId").value(42))
                .andReturn().getResponse().getContentAsString();

        assertThat(mapper.readTree(body).get("totalAmount").isNumber()).isTrue();
        assertThat(mapper.readValue(body, OrderRecord.class).orderId()).isEqualTo(ORDER_ID);
        verify(service).findOwned(ORDER_ID, USER_ID);
    }

    @Test
    void listSerializesNestedOrderIdsAsTextButKeepsPaginationNumeric() throws Exception {
        when(service.findForUser(USER_ID, 0, 10)).thenReturn(
                new OrderApplicationService.OrderPage(List.of(order()), 1L, 0, 10));

        mvc.perform(get("/api/orders").header("X-User-Id", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders[0].orderId").isString())
                .andExpect(jsonPath("$.orders[0].orderId").value(ORDER_ID_TEXT))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10));
    }

    @Test
    void cancelPreservesExactIdFromTheBrowserPathToItsResponse() throws Exception {
        when(service.cancelOwned(ORDER_ID, USER_ID)).thenReturn(
                new OrderApplicationService.CancelOrderResult(ORDER_ID, "CANCELED"));

        mvc.perform(post("/api/orders/{orderId}/cancel", ORDER_ID_TEXT)
                        .header("X-User-Id", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").isString())
                .andExpect(jsonPath("$.orderId").value(ORDER_ID_TEXT));

        verify(service).cancelOwned(ORDER_ID, USER_ID);
    }

    @Test
    void creationResultsPreserveOrderIdsAndRemainReadableByJavaContracts() throws Exception {
        var created = new OrderApplicationService.CreateOrderResult(
                ORDER_ID, "RESERVED", "created", false);
        String body = mapper.writeValueAsString(created);
        assertExactId(mapper.readTree(body));
        assertThat(mapper.readValue(body, OrderCreateResponse.class).orderId()).isEqualTo(ORDER_ID);

        var accepted = new RedisOrderApplicationService.RedisOrderResult(
                ORDER_ID, 7L, "READY", "accepted", false);
        JsonNode asyncJson = mapper.readTree(mapper.writeValueAsString(accepted));
        assertExactId(asyncJson);
        assertThat(asyncJson.get("commandId").isNumber()).isTrue();
    }

    private static void assertExactId(JsonNode json) {
        assertThat(json.get("orderId").isTextual()).isTrue();
        assertThat(json.get("orderId").textValue()).isEqualTo(ORDER_ID_TEXT);
    }

    private static OrderRecord order() {
        return new OrderRecord(ORDER_ID, USER_ID, new BigDecimal("19.90"), 1800L,
                OrderStatus.PENDING_PAYMENT, ReservationStatus.RESERVED, null, null, null, null,
                List.of(new OrderItemRecord(1001L, 1L, 1, new BigDecimal("19.90"), "Test", null)));
    }
}
