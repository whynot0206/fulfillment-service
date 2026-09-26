package com.why.fulfillment.order.service;

import com.why.fulfillment.api.inventory.InventoryClient;
import com.why.fulfillment.api.order.OrderCreateItem;
import com.why.fulfillment.api.order.OrderCreateRequest;
import com.why.fulfillment.order.domain.OrderItemRecord;
import com.why.fulfillment.order.domain.OrderRecord;
import com.why.fulfillment.order.domain.OrderStatus;
import com.why.fulfillment.order.domain.ReservationStatus;
import com.why.fulfillment.order.repository.OrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class OrderResolveCreateTest {
    private final OrderRepository repository = mock(OrderRepository.class);
    private final InventoryClient inventory = mock(InventoryClient.class);
    private final OrderApplicationService service = new OrderApplicationService(repository, inventory);

    @AfterEach
    void neverCallsInventory() {
        verifyNoInteractions(inventory);
    }

    @Test
    void absenceIsNotARejectionAndDoesNotInsertAnything() {
        when(repository.find(10L)).thenReturn(Optional.empty());

        var result = service.resolveCreateFromCommerce(request());

        assertThat(result.state()).isEqualTo("NOT_FOUND");
        assertThat(result.replayed()).isFalse();
        assertReadOnly();
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING_PAYMENT, RESERVING, RESERVING",
            "PENDING_PAYMENT, RESERVED, RESERVED",
            "PENDING_PAYMENT, FAILED, FAILED",
            "PENDING_PAYMENT, COMPENSATED, COMPENSATED",
            "PENDING_PAYMENT, PENDING_COMPENSATION, PENDING_COMPENSATION",
            "PAID, RESERVED, PAID",
            "CANCELED, RESERVING, CANCELED",
            "CANCELED, RESERVED, CANCELED",
            "CANCELED, FAILED, FAILED",
            "CANCELED, COMPENSATED, COMPENSATED",
            "CANCELED, PENDING_COMPENSATION, PENDING_COMPENSATION",
            "CLOSED, RESERVED, CLOSED"
    })
    void resolvesCurrentFactsWithoutMistakingUnfinishedOrCanceledOrdersForSuccess(
            OrderStatus status, ReservationStatus reservation, String expected) {
        when(repository.find(10L)).thenReturn(Optional.of(order(status, reservation)));

        var result = service.resolveCreateFromCommerce(request());

        assertThat(result.state()).isEqualTo(expected);
        assertThat(result.replayed()).isTrue();
        assertThat(result.orderId()).isEqualTo(10L);
        assertReadOnly();
    }

    @ParameterizedTest
    @MethodSource("differentPayloads")
    void sameIdWithADifferentPayloadIsConflictEvenIfPaid(OrderCreateRequest request) {
        when(repository.find(10L)).thenReturn(Optional.of(order(OrderStatus.PAID, ReservationStatus.RESERVED)));

        assertThat(service.resolveCreateFromCommerce(request).state()).isEqualTo("CONFLICT");

        assertReadOnly();
    }

    static Stream<OrderCreateRequest> differentPayloads() {
        return Stream.of(
                new OrderCreateRequest(10L, 21L, new BigDecimal("10.00"), 1800L, request().items()),
                new OrderCreateRequest(10L, 20L, new BigDecimal("10.00"), 60L, request().items()),
                new OrderCreateRequest(10L, 20L, new BigDecimal("12.00"), 1800L,
                        List.of(item(1001L, 1L, 2, "6.00", "keyboard"))),
                new OrderCreateRequest(10L, 20L, new BigDecimal("10.00"), 1800L,
                        List.of(item(1002L, 1L, 2, "5.00", "keyboard"))),
                new OrderCreateRequest(10L, 20L, new BigDecimal("10.00"), 1800L,
                        List.of(item(1001L, 2L, 2, "5.00", "keyboard"))));
    }

    @Test
    void usesCreationNormalizationAndIgnoresOnlyDisplayText() {
        OrderRecord stored = new OrderRecord(10L, 20L, new BigDecimal("10.00"), 1800L,
                OrderStatus.PENDING_PAYMENT, ReservationStatus.RESERVED, null, null, null, null,
                List.of(new OrderItemRecord(1001L, 1L, 1, new BigDecimal("5.00"), "old name", "old spec"),
                        new OrderItemRecord(1002L, 1L, 1, new BigDecimal("5.00"), null, null)));
        when(repository.find(10L)).thenReturn(Optional.of(stored));
        OrderCreateRequest reordered = new OrderCreateRequest(10L, 20L, new BigDecimal("10.0"), null,
                List.of(item(1002L, 1L, 1, "5.0", "changed name"),
                        item(1001L, 1L, 1, "5.0", "changed name")));

        assertThat(service.resolveCreateFromCommerce(reordered).state()).isEqualTo("RESERVED");

        assertReadOnly();
    }

    @Test
    void invalidSnapshotIsRejectedBeforeReadingAnything() {
        assertThatThrownBy(() -> service.resolveCreateFromCommerce(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.resolveCreateFromCommerce(new OrderCreateRequest(
                10L, 20L, new BigDecimal("9.00"), 1800L, request().items())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.resolveCreateFromCommerce(new OrderCreateRequest(
                10L, 20L, new BigDecimal("10.00"), 1800L, Arrays.asList((OrderCreateItem) null))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    private void assertReadOnly() {
        verify(repository).find(10L);
        verifyNoMoreInteractions(repository);
    }

    private static OrderCreateRequest request() {
        return new OrderCreateRequest(10L, 20L, new BigDecimal("10.00"), 1800L,
                List.of(item(1001L, 1L, 2, "5.00", "keyboard")));
    }

    private static OrderCreateItem item(long sku, long spu, int count, String price, String name) {
        return new OrderCreateItem(sku, spu, count, new BigDecimal(price), name, null);
    }

    private static OrderRecord order(OrderStatus status, ReservationStatus reservation) {
        return new OrderRecord(10L, 20L, new BigDecimal("10.00"), 1800L,
                status, reservation, "saved reason", null, null, null,
                List.of(new OrderItemRecord(1001L, 1L, 2, new BigDecimal("5.00"), "keyboard", null)));
    }
}
