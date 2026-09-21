package com.why.fulfillment.inventory;

import com.why.fulfillment.api.inventory.InventoryQueryResponse;
import com.why.fulfillment.inventory.service.InventoryIdempotencyConflictException;
import com.why.fulfillment.inventory.service.InventoryReservationService;
import com.why.fulfillment.inventory.web.InventoryController;
import com.why.fulfillment.inventory.web.PublicInventoryController;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class InventoryControllerTest {

    private MockMvc mockMvc;

    @Mock
    private InventoryReservationService reservationService;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new InventoryController(reservationService),
                new PublicInventoryController(reservationService)).build();
    }

    @Test
    void internalReserveReturnsReservedAndIsSafeToRetry() throws Exception {
        mockMvc.perform(post("/internal/inventory/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":42,"items":[
                                  {"skuId":10,"spuId":1,"count":2},
                                  {"skuId":20,"spuId":1,"count":1}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(reservationService).reserve(eq(42L), any());
    }

    @Test
    void idempotencyConflictIsReturnedAsRejected() throws Exception {
        doThrow(new InventoryIdempotencyConflictException("different payload"))
                .when(reservationService).reserve(eq(42L), any());

        mockMvc.perform(post("/internal/inventory/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" +
                                "\"orderId\":42," +
                                "\"items\":[{\"skuId\":10,\"spuId\":1,\"count\":3}]" +
                                "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.error").value("different payload"));
    }

    @Test
    void queryIsAvailableOnInternalAndExternalPaths() throws Exception {
        when(reservationService.query(10L)).thenReturn(new InventoryQueryResponse(10L, 1L, 8, 2));

        mockMvc.perform(get("/internal/inventory/query/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skuId").value(10))
                .andExpect(jsonPath("$.stock").value(8))
                .andExpect(jsonPath("$.lockStock").value(2));
        mockMvc.perform(get("/api/inventory/skus/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skuId").value(10));
    }
}
