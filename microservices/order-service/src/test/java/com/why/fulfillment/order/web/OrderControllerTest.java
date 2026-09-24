package com.why.fulfillment.order.web;

import com.why.fulfillment.order.domain.OrderItemRecord;
import com.why.fulfillment.order.domain.OrderRecord;
import com.why.fulfillment.order.domain.OrderStatus;
import com.why.fulfillment.order.domain.ReservationStatus;
import com.why.fulfillment.order.service.OrderApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderControllerTest {

    private final OrderApplicationService service = mock(OrderApplicationService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new OrderController(service))
            .setControllerAdvice(new OrderApiExceptionHandler())
            .build();

    @Test
    void replayedReservationReturnsOk() throws Exception {
        when(service.createPending(any())).thenReturn(
                new OrderApplicationService.CreateOrderResult(10L, "RESERVED", "inventory reserved", true));

        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":10,"userId":20,"totalAmount":10.00,
                                 "items":[{"skuId":1001,"spuId":1,"count":2,"price":5.00}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    void invalidCreateRequestReturnsBadRequest() throws Exception {
        when(service.createPending(any())).thenThrow(new IllegalArgumentException("price is required"));

        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":10,"userId":20,"totalAmount":10.00,
                                 "items":[{"skuId":1001,"spuId":1,"count":2}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("price is required"));
    }

    /**
     * The read paths take their identity from the header the gateway injects, never from the
     * path or a query parameter. If this ever regresses to {@code /api/orders/{orderId}}
     * without the header, every order in the system becomes readable by id.
     */
    @Test
    void orderDetailIsScopedToTheHeaderIdentity() throws Exception {
        when(service.findOwned(10L, 20L)).thenReturn(Optional.of(order(20L)));
        when(service.findOwned(10L, 21L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/orders/10").header("X-User-Id", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(10));

        // Same order id, different caller: 404, not 403 — a 403 would confirm the id exists.
        mvc.perform(get("/api/orders/10").header("X-User-Id", "21"))
                .andExpect(status().isNotFound());
    }

    @Test
    void readWithoutTheGatewayIdentityHeaderIsUnauthorized() throws Exception {
        mvc.perform(get("/api/orders/10"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized());

        verify(service, never()).findOwned(anyLong(), anyLong());
        verify(service, never()).findForUser(anyLong(), anyInt(), anyInt());
    }

    @Test
    void listPassesThroughPagingParameters() throws Exception {
        when(service.findForUser(20L, 2, 5)).thenReturn(
                new OrderApplicationService.OrderPage(List.of(order(20L)), 11L, 2, 5));

        mvc.perform(get("/api/orders").header("X-User-Id", "20").param("page", "2").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(11))
                .andExpect(jsonPath("$.orders[0].orderId").value(10));
    }

    @Test
    void cancelMapsOwnershipAndPaymentConflict() throws Exception {
        when(service.cancelOwned(10L, 20L)).thenReturn(
                new OrderApplicationService.CancelOrderResult(10L, "CANCELED"));
        when(service.cancelOwned(10L, 21L)).thenReturn(
                new OrderApplicationService.CancelOrderResult(10L, "NOT_FOUND"));
        when(service.cancelOwned(11L, 20L)).thenReturn(
                new OrderApplicationService.CancelOrderResult(11L, "CONFLICT"));

        mvc.perform(post("/api/orders/10/cancel").header("X-User-Id", "20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CANCELED"));
        mvc.perform(post("/api/orders/10/cancel").header("X-User-Id", "21"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/orders/11/cancel").header("X-User-Id", "20"))
                .andExpect(status().isConflict());
    }

    private static OrderRecord order(long userId) {
        return new OrderRecord(10L, userId, new BigDecimal("10.00"), 1800L,
                OrderStatus.PENDING_PAYMENT, ReservationStatus.RESERVED, null, null, null, null,
                List.of(new OrderItemRecord(1001L, 1L, 2, new BigDecimal("5.00"), "机械键盘", null)));
    }
}
