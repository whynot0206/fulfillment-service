package com.why.fulfillment.inventory;

import com.why.fulfillment.api.inventory.InventoryReserveItem;
import com.why.fulfillment.inventory.entity.SkuStockLock;
import com.why.fulfillment.inventory.mapper.InventoryReservationFenceMapper;
import com.why.fulfillment.inventory.mapper.SkuStockLockMapper;
import com.why.fulfillment.inventory.mapper.SkuStockMapper;
import com.why.fulfillment.inventory.service.InventoryReservationRejectedException;
import com.why.fulfillment.inventory.service.InventoryReservationService;
import com.why.fulfillment.inventory.service.impl.InventoryReservationServiceImpl;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies Spring's actual transaction advice; database locking is verified by real acceptance runs. */
class InventoryReservationTransactionTest {
    private final SkuStockMapper stock = mock(SkuStockMapper.class);
    private final SkuStockLockMapper locks = mock(SkuStockLockMapper.class);
    private final InventoryReservationFenceMapper fence = mock(InventoryReservationFenceMapper.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final TransactionStatus transaction = new SimpleTransactionStatus();
    private final AnnotationTransactionAttributeSource attributes = new AnnotationTransactionAttributeSource();
    private final InventoryReservationServiceImpl target = new InventoryReservationServiceImpl(stock, locks, fence);

    @ParameterizedTest
    @ValueSource(strings = {"reserve", "release", "confirm"})
    void everyMutationUsesReadCommittedAndOwnsTheFenceBeforeReadingLockRows(String operation) {
        when(fence.selectStatusForUpdate(10L)).thenReturn(InventoryReservationFenceMapper.ACTIVE);
        when(locks.selectByOrderIdForUpdate(10L)).thenReturn(operation.equals("confirm")
                ? List.of(locked(1L, 1001L)) : List.of());
        when(locks.insert(any())).thenReturn(1);
        when(stock.reserve(1001L, 1)).thenReturn(1);
        when(locks.markConfirmedIfLocked(1L)).thenReturn(1);
        when(stock.confirm(1001L, 1)).thenReturn(1);
        InventoryReservationService advised = advisedService();

        switch (operation) {
            case "reserve" -> advised.reserve(10L, List.of(new InventoryReserveItem(1001L, 1L, 1)));
            case "release" -> advised.release(10L);
            case "confirm" -> advised.confirm(10L);
            default -> throw new AssertionError("unexpected operation");
        }

        var sequence = inOrder(transactions, fence, locks);
        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        sequence.verify(transactions).getTransaction(definition.capture());
        sequence.verify(fence).ensureExists(10L);
        sequence.verify(fence).selectStatusForUpdate(10L);
        sequence.verify(locks).selectByOrderIdForUpdate(10L);
        sequence.verify(transactions).commit(transaction);
        assertThat(definition.getValue().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThat(definition.getValue().isReadOnly()).isFalse();
        verify(transactions, never()).rollback(any());
    }

    @Test
    void aLaterSkuRejectionRollsBackTheWholeTransactionWithoutAnAutomaticRetry() {
        when(fence.selectStatusForUpdate(10L)).thenReturn(InventoryReservationFenceMapper.ACTIVE);
        when(locks.selectByOrderIdForUpdate(10L)).thenReturn(List.of());
        when(locks.insert(any())).thenReturn(1);
        when(stock.reserve(1001L, 1)).thenReturn(1);
        when(stock.reserve(1002L, 1)).thenReturn(0);
        InventoryReservationService advised = advisedService();

        assertThatThrownBy(() -> advised.reserve(10L, List.of(
                new InventoryReserveItem(1002L, 1L, 1), new InventoryReserveItem(1001L, 1L, 1))))
                .isInstanceOf(InventoryReservationRejectedException.class)
                .hasMessageContaining("insufficient stock for sku 1002");

        var sequence = inOrder(stock, transactions);
        sequence.verify(stock).reserve(1001L, 1);
        sequence.verify(stock).reserve(1002L, 1);
        sequence.verify(transactions).rollback(transaction);
        verify(transactions, never()).commit(any());
        verify(fence).ensureExists(10L);
        verify(locks).selectByOrderIdForUpdate(10L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"release", "confirm"})
    void aStockWriteFailureRollsBackTheLockTransitionAndCannotPublishATerminalFence(String operation) {
        when(fence.selectStatusForUpdate(10L)).thenReturn(InventoryReservationFenceMapper.ACTIVE);
        when(locks.selectByOrderIdForUpdate(10L)).thenReturn(List.of(locked(1L, 1001L)));
        when(locks.markReleasedIfLocked(1L)).thenReturn(1);
        when(locks.markConfirmedIfLocked(1L)).thenReturn(1);
        when(stock.release(1001L, 1)).thenThrow(new IllegalStateException("stock write failed"));
        when(stock.confirm(1001L, 1)).thenThrow(new IllegalStateException("stock write failed"));
        InventoryReservationService advised = advisedService();

        assertThatThrownBy(() -> {
            if (operation.equals("release")) advised.release(10L);
            else advised.confirm(10L);
        }).isInstanceOf(IllegalStateException.class).hasMessage("stock write failed");

        verify(transactions).rollback(transaction);
        verify(transactions, never()).commit(any());
        verify(fence, never()).updateStatus(any(), anyInt());
    }

    @Test
    void readOnlyViewsKeepTheirExistingIsolationAndDoNotAcquireMutationFences() throws Exception {
        for (String method : List.of("reservationItems", "query")) {
            var definition = attributes.getTransactionAttribute(
                    InventoryReservationServiceImpl.class.getMethod(method, Long.class), target.getClass());
            assertThat(definition).isNotNull();
            assertThat(definition.isReadOnly()).isTrue();
            assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
        }
    }

    @Test
    void mutationStillUsesCurrentReadsAndSkuOrderingRatherThanAnUnprotectedSnapshot() throws Exception {
        var lockRead = SkuStockLockMapper.class.getMethod("selectByOrderIdForUpdate", Long.class)
                .getAnnotation(Select.class);
        assertThat(String.join(" ", lockRead.value())).contains("order by sku_id for update");
        var fenceRead = InventoryReservationFenceMapper.class.getMethod("selectStatusForUpdate", Long.class)
                .getAnnotation(Select.class);
        assertThat(String.join(" ", fenceRead.value())).contains("where order_id = #{orderId} for update");
    }

    private InventoryReservationService advisedService() {
        when(transactions.getTransaction(any())).thenReturn(transaction);
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(transactions, attributes));
        return (InventoryReservationService) factory.getProxy();
    }

    private static SkuStockLock locked(long id, long skuId) {
        SkuStockLock lock = new SkuStockLock();
        lock.setId(id);
        lock.setOrderId(10L);
        lock.setSkuId(skuId);
        lock.setSpuId(1L);
        lock.setCount(1);
        lock.setStatus(SkuStockLock.LOCKED);
        return lock;
    }
}
