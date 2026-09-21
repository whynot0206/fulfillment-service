package com.why.fulfillment.order.web;

import com.why.fulfillment.order.service.OrderApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
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
}
