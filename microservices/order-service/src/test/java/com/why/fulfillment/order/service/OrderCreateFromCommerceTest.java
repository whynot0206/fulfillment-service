package com.why.fulfillment.order.service;

import com.why.fulfillment.api.inventory.InventoryClient;
import com.why.fulfillment.api.inventory.InventoryReserveResponse;
import com.why.fulfillment.api.order.OrderCreateItem;
import com.why.fulfillment.api.order.OrderCreateRequest;
import com.why.fulfillment.order.domain.OrderItemRecord;
import com.why.fulfillment.order.domain.OrderRecord;
import com.why.fulfillment.order.domain.OrderStatus;
import com.why.fulfillment.order.domain.ReservationStatus;
import com.why.fulfillment.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Commerce-facing creation path: price snapshot, total consistency, and ownership scoped
 * reads.
 */
class OrderCreateFromCommerceTest {

    private final OrderRepository repository = mock(OrderRepository.class);
    private final InventoryClient inventory = mock(InventoryClient.class);
    private final OrderApplicationService service = new OrderApplicationService(repository, inventory);

    private static OrderCreateRequest request(BigDecimal total, String name) {
        return new OrderCreateRequest(10L, 20L, total, 1800L,
                List.of(new OrderCreateItem(1001L, 1L, 2, new BigDecimal("5.00"), name, "{\"色\":\"黑\"}")));
    }

    @Test
    void snapshotIsPersistedWithTheOrderLine() {
        when(inventory.reserve(any())).thenReturn(InventoryReserveResponse.reserved());
        when(repository.updateReservation(10L, ReservationStatus.RESERVED, null)).thenReturn(true);

        service.createFromCommerce(request(new BigDecimal("10.00"), "机械键盘"));

        verify(repository).insertPending(eq(10L), eq(20L), eq(new BigDecimal("10.00")), eq(1800L),
                eq(List.of(new OrderItemRecord(1001L, 1L, 2, new BigDecimal("5.00"),
                        "机械键盘", "{\"色\":\"黑\"}"))));
    }

    /**
     * The point of the whole snapshot design: a retry sent after the merchant renamed the
     * product is still the same order.
     *
     * <p>If {@code samePayload} ever starts comparing {@code nameSnapshot}, this test fails
     * with CONFLICT instead of RESERVED. That failure mode is worth protecting against,
     * because in production it looks like "some users occasionally cannot place an order" and
     * nothing in the logs points at a rename.</p>
     */
    @Test
    void replayAfterProductRenameIsStillTheSameOrder() {
        doThrow(new DuplicateKeyException("duplicate order"))
                .when(repository).insertPending(anyLong(), anyLong(), any(), anyLong(), any());
        OrderRecord stored = new OrderRecord(10L, 20L, new BigDecimal("10.00"), 1800L,
                OrderStatus.PENDING_PAYMENT, ReservationStatus.RESERVING, null, null, null, null,
                List.of(new OrderItemRecord(1001L, 1L, 2, new BigDecimal("5.00"),
                        "机械键盘", "{\"色\":\"黑\"}")));
        when(repository.find(10L)).thenReturn(Optional.of(stored));
        when(inventory.reserve(any())).thenReturn(InventoryReserveResponse.reserved());
        when(repository.updateReservation(10L, ReservationStatus.RESERVED, null)).thenReturn(true);

        OrderApplicationService.CreateOrderResult result =
                service.createFromCommerce(request(new BigDecimal("10.00"), "机械键盘（2026 新款）"));

        assertThat(result.state()).isEqualTo("RESERVED");
        assertThat(result.replayed()).isTrue();
    }

    /** A changed price is a different order, snapshot or not. */
    @Test
    void replayWithADifferentPriceIsStillAConflict() {
        doThrow(new DuplicateKeyException("duplicate order"))
                .when(repository).insertPending(anyLong(), anyLong(), any(), anyLong(), any());
        OrderRecord stored = new OrderRecord(10L, 20L, new BigDecimal("10.00"), 1800L,
                OrderStatus.PENDING_PAYMENT, ReservationStatus.RESERVED, null, null, null, null,
                List.of(new OrderItemRecord(1001L, 1L, 2, new BigDecimal("6.00"), "机械键盘", null)));
        when(repository.find(10L)).thenReturn(Optional.of(stored));

        OrderApplicationService.CreateOrderResult result =
                service.createFromCommerce(request(new BigDecimal("10.00"), "机械键盘"));

        assertThat(result.state()).isEqualTo("CONFLICT");
        verify(inventory, never()).reserve(any());
    }

    @Test
    void totalThatDisagreesWithTheItemsIsRejectedBeforePersistence() {
        assertThatThrownBy(() -> service.createFromCommerce(request(new BigDecimal("9.00"), "机械键盘")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match the sum of items");

        verify(repository, never()).insertPending(anyLong(), anyLong(), any(), anyLong(), any());
        verify(inventory, never()).reserve(any());
    }

    /** 10.0 and 10.00 are the same money. Comparing with equals() would reject this. */
    @Test
    void trailingZeroesDoNotMakeTheTotalWrong() {
        when(inventory.reserve(any())).thenReturn(InventoryReserveResponse.reserved());
        when(repository.updateReservation(10L, ReservationStatus.RESERVED, null)).thenReturn(true);

        OrderApplicationService.CreateOrderResult result =
                service.createFromCommerce(request(new BigDecimal("10.0"), "机械键盘"));

        assertThat(result.state()).isEqualTo("RESERVED");
    }

    @Test
    void oversizedSnapshotIsRejectedRatherThanTruncated() {
        OrderCreateRequest tooLong = new OrderCreateRequest(10L, 20L, new BigDecimal("10.00"), 1800L,
                List.of(new OrderCreateItem(1001L, 1L, 2, new BigDecimal("5.00"),
                        "名".repeat(129), null)));

        assertThatThrownBy(() -> service.createFromCommerce(tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nameSnapshot");

        verify(repository, never()).insertPending(anyLong(), anyLong(), any(), anyLong(), any());
    }

    @Test
    void anotherUsersOrderReadsAsAbsent() {
        OrderRecord someoneElses = new OrderRecord(10L, 999L, new BigDecimal("10.00"), 1800L,
                OrderStatus.PENDING_PAYMENT, ReservationStatus.RESERVED, null, null, null, null, List.of());
        when(repository.find(10L)).thenReturn(Optional.of(someoneElses));

        assertThat(service.findOwned(10L, 20L)).isEmpty();
        assertThat(service.findOwned(10L, 999L)).isPresent();
    }

    @Test
    void pageTooDeepIsRejectedInsteadOfScanningTheWholeHistory() {
        assertThatThrownBy(() -> service.findForUser(20L, 5000, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too deep");

        verify(repository, never()).findByUser(anyLong(), anyInt(), anyInt());
    }

    /** An oversized page size is clamped, not rejected — it is not an attack, just a bad default. */
    @Test
    void pageSizeIsClampedToTheMaximum() {
        when(repository.findByUser(20L, 50, 0)).thenReturn(List.of());

        OrderApplicationService.OrderPage page = service.findForUser(20L, 0, 10_000);

        assertThat(page.size()).isEqualTo(50);
        verify(repository).findByUser(20L, 50, 0);
    }
}
