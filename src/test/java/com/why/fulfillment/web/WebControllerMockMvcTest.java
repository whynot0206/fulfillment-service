package com.why.fulfillment.web;

import com.why.fulfillment.inventory.entity.SkuStock;
import com.why.fulfillment.inventory.mapper.SkuStockMapper;
import com.why.fulfillment.inventory.mapper.SkuStockLockMapper;
import com.why.fulfillment.inventory.reconciliation.StockReconciliationService;
import com.why.fulfillment.inventory.service.StockReservationItem;
import com.why.fulfillment.order.entity.Order;
import com.why.fulfillment.order.mapper.AsyncOrderCommandMapper;
import com.why.fulfillment.order.service.OrderService;
import com.why.fulfillment.order.mapper.OrderMapper;
import com.why.fulfillment.order.mapper.OrderOutboxEventMapper;
import com.why.fulfillment.payment.service.PaymentCallbackService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        OrderController.class,
        PaymentCallbackController.class,
        InventoryController.class
})
@Import(ApiExceptionHandler.class)
class WebControllerMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    @MockBean
    private PaymentCallbackService paymentCallbackService;

    @MockBean
    private SkuStockMapper stockMapper;

    // FulfillmentApplication contains a package-wide @MapperScan. WebMvcTest does not create a
    // SqlSessionFactory, so all remaining mapper definitions must be replaced in this slice.
    @MockBean
    private SkuStockLockMapper stockLockMapper;

    @MockBean
    private OrderMapper orderMapper;

    @MockBean
    private OrderOutboxEventMapper outboxEventMapper;

    @MockBean
    private AsyncOrderCommandMapper asyncOrderCommandMapper;

    @MockBean
    private StockReconciliationService stockReconciliationService;

    @Test
    void createOrderReturnsCreatedAndMapsRequest() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orderId": 1001,
                                  "userId": 7,
                                  "totalAmount": 12.50,
                                  "timeoutSeconds": 60,
                                  "items": [{"skuId": 2001, "spuId": 3001, "count": 2}]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(1001))
                .andExpect(jsonPath("$.message").doesNotExist());

        var orderCaptor = org.mockito.ArgumentCaptor.forClass(Order.class);
        var itemsCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        var timeoutCaptor = org.mockito.ArgumentCaptor.forClass(Duration.class);
        verify(orderService).createPending(orderCaptor.capture(), itemsCaptor.capture(), timeoutCaptor.capture());

        assertEquals(1001L, orderCaptor.getValue().getOrderId());
        assertEquals(7L, orderCaptor.getValue().getUserId());
        assertEquals(new BigDecimal("12.50"), orderCaptor.getValue().getTotalAmount());
        assertEquals(Duration.ofSeconds(60), timeoutCaptor.getValue());
        @SuppressWarnings("unchecked")
        List<StockReservationItem> items = (List<StockReservationItem>) itemsCaptor.getValue();
        assertEquals(List.of(new StockReservationItem(2001L, 3001L, 2)), items);
    }

    @Test
    void createOrderRejectsNullItemAndDoesNotCallService() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orderId": 1001,
                                  "userId": 7,
                                  "totalAmount": 12.50,
                                  "items": [null]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("each item must contain positive skuId, spuId and count"));

        verify(orderService, never()).createPending(any(), any(), any());
    }

    @Test
    void createOrderRejectsInvalidItemFieldsAndTimeout() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orderId": 1001,
                                  "userId": 7,
                                  "totalAmount": 12.50,
                                  "timeoutSeconds": 0,
                                  "items": [{"skuId": 2001, "spuId": 3001, "count": 1}]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("timeoutSeconds must be positive"));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orderId": 1001,
                                  "userId": 7,
                                  "totalAmount": 12.50,
                                  "items": [{"skuId": null, "spuId": 3001, "count": 0}]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("each item must contain positive skuId, spuId and count"));

        verify(orderService, never()).createPending(any(), any(), any());
    }

    @Test
    void paymentCallbackReturnsAcceptedResult() throws Exception {
        when(paymentCallbackService.acceptSuccess(1001L, "trade-1001")).thenReturn(true);

        mockMvc.perform(post("/api/payments/callbacks/success")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId": 1001, "outTradeNo": "trade-1001"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(true))
                .andExpect(jsonPath("$.message").doesNotExist());

        verify(paymentCallbackService).acceptSuccess(1001L, "trade-1001");
    }

    @Test
    void paymentCallbackRejectsMissingOrBlankFields() throws Exception {
        mockMvc.perform(post("/api/payments/callbacks/success")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("positive orderId and outTradeNo are required"));

        mockMvc.perform(post("/api/payments/callbacks/success")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":1001,\"outTradeNo\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("positive orderId and outTradeNo are required"));

        verify(paymentCallbackService, never()).acceptSuccess(any(), any());
    }

    @Test
    void inventoryReturnsStockAndHandlesMissingSku() throws Exception {
        SkuStock stock = new SkuStock();
        stock.setSkuId(2001L);
        stock.setSpuId(3001L);
        stock.setStock(8);
        stock.setLockStock(2);
        when(stockMapper.selectById(2001L)).thenReturn(stock);

        mockMvc.perform(get("/api/inventory/skus/2001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.skuId").value(2001))
                .andExpect(jsonPath("$.data.stock").value(8))
                .andExpect(jsonPath("$.data.lockStock").value(2));

        when(stockMapper.selectById(4040L)).thenReturn(null);
        mockMvc.perform(get("/api/inventory/skus/4040"))
                .andExpect(status().isNotFound());
    }

    @Test
    void inventoryRejectsNonPositiveSku() throws Exception {
        mockMvc.perform(get("/api/inventory/skus/0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("skuId must be positive"));

        verify(stockMapper, never()).selectById(0L);
    }
}
