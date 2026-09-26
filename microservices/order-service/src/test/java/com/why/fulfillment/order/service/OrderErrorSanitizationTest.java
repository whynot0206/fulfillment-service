package com.why.fulfillment.order.service;

import com.why.fulfillment.api.inventory.InventoryClient;
import com.why.fulfillment.api.inventory.InventoryReleaseResponse;
import com.why.fulfillment.api.inventory.InventoryReserveResponse;
import com.why.fulfillment.order.domain.OrderRecord;
import com.why.fulfillment.order.domain.OrderStatus;
import com.why.fulfillment.order.domain.ReservationStatus;
import com.why.fulfillment.order.repository.OrderRepository;
import com.why.fulfillment.order.web.OrderController;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderErrorSanitizationTest {
    private static final String URL = "http://inventory-private.invalid:18082/internal/inventory/reserve?debug=private-url-value";
    private static final String TOKEN = "fake-internal-secret-for-regression";
    private static final String BODY = "{\"error\":\"private-response-body\",\"token\":\"" + TOKEN + "\"}";
    private final OrderRepository repository = mock(OrderRepository.class);
    private final InventoryClient inventory = mock(InventoryClient.class);
    private final OrderApplicationService service = new OrderApplicationService(repository, inventory);
    private final OrderApplicationService.CreateOrderCommand command = new OrderApplicationService.CreateOrderCommand(
            10L, 20L, BigDecimal.TEN, 1800L,
            List.of(new OrderApplicationService.OrderItemCommand(1001L, 1L, 2, new BigDecimal("5.00"))));

    @ParameterizedTest
    @ValueSource(ints = {400, 503})
    void reserveFeignFailurePersistsOnlyTypeAndStatusWithoutChangingTheDecision(int httpStatus) throws Exception {
        FeignException failure = remoteFailure(httpStatus);
        when(inventory.reserve(any())).thenThrow(failure);
        when(repository.updateReservation(eq(10L), eq(ReservationStatus.FAILED), any())).thenReturn(true);
        when(repository.markReservingForCompensation(eq(10L), any())).thenReturn(true);
        when(inventory.release(any())).thenReturn(InventoryReleaseResponse.released());

        var result = service.createPending(command);

        assertThat(result.state()).isEqualTo(httpStatus == 400 ? "FAILED" : "COMPENSATED");
        assertSanitized(result.message());
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        if (httpStatus == 400) {
            verify(repository).updateReservation(eq(10L), eq(ReservationStatus.FAILED), stored.capture());
        } else {
            verify(repository).markReservingForCompensation(eq(10L), stored.capture());
            verify(repository).markCompensatedIfPending(eq(10L), stored.capture());
        }
        for (String summary : stored.getAllValues()) {
            assertThat(summary).contains(failure.getClass().getSimpleName(), "HTTP " + httpStatus);
            assertSanitized(summary);
        }
        assertPublicDetailIsSanitized(stored.getValue());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void foregroundAndBackgroundCompensationFeignFailuresNeverPersistTheRemoteBody(boolean background) throws Exception {
        when(inventory.release(any())).thenThrow(remoteFailure(503));
        if (background) {
            service.retryPendingCompensation(10L);
        } else {
            when(inventory.reserve(any())).thenReturn(InventoryReserveResponse.unknown("reservation response unavailable"));
            when(repository.markReservingForCompensation(eq(10L), any())).thenReturn(true);
            var result = service.createPending(command);
            assertThat(result.state()).isEqualTo("PENDING_COMPENSATION");
            assertSanitized(result.message());
        }

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(repository).markCompensationPending(eq(10L), stored.capture());
        assertThat(stored.getValue()).contains("compensation call failed", "HTTP 503", "ServiceUnavailable");
        assertSanitized(stored.getValue());
        assertPublicDetailIsSanitized(stored.getValue());
    }

    @Test
    void nonFeignExceptionMessagesAndNestedCausesAreNotPersistedEither() throws Exception {
        when(inventory.reserve(any())).thenThrow(new IllegalStateException(URL + " " + TOKEN,
                new IllegalArgumentException(BODY)));
        when(repository.markReservingForCompensation(eq(10L), any())).thenReturn(true);
        when(inventory.release(any())).thenThrow(new IllegalArgumentException(BODY));

        assertThat(service.createPending(command).state()).isEqualTo("PENDING_COMPENSATION");

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(repository).markReservingForCompensation(eq(10L), stored.capture());
        verify(repository).markCompensationPending(eq(10L), stored.capture());
        assertThat(stored.getValue()).contains("IllegalStateException", "IllegalArgumentException");
        stored.getAllValues().forEach(OrderErrorSanitizationTest::assertSanitized);
        assertPublicDetailIsSanitized(stored.getValue());
    }

    private static FeignException remoteFailure(int status) {
        Request request = Request.create(Request.HttpMethod.POST, URL,
                Map.of("X-Internal-Service-Token", List.of(TOKEN)), new byte[0],
                StandardCharsets.UTF_8, new RequestTemplate());
        FeignException exception = FeignException.errorStatus("InventoryClient#reserve", Response.builder()
                .request(request).status(status).reason("upstream failure").headers(Map.of())
                .body(BODY, StandardCharsets.UTF_8).build());
        // Establish that this is the original leak, not a mock exception with an empty message.
        assertThat(exception.getMessage()).contains(URL, TOKEN, "private-response-body");
        return exception;
    }

    private static void assertSanitized(String value) {
        assertThat(value).doesNotContain(URL, TOKEN, "private-response-body", "private-url-value",
                "inventory-private.invalid", "X-Internal-Service-Token");
    }

    private void assertPublicDetailIsSanitized(String storedError) throws Exception {
        when(repository.find(10L)).thenReturn(Optional.of(new OrderRecord(10L, 20L, BigDecimal.TEN, 1800L,
                OrderStatus.CANCELED, ReservationStatus.PENDING_COMPENSATION, storedError,
                null, null, null, List.of())));
        String json = MockMvcBuilders.standaloneSetup(new OrderController(service)).build()
                .perform(get("/api/orders/10").header("X-User-Id", "20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reservationError").value(storedError))
                .andReturn().getResponse().getContentAsString();
        assertSanitized(json);
    }
}
