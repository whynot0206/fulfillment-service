package com.why.fulfillment.order.repository;

import com.why.fulfillment.order.domain.OrderStatus;
import com.why.fulfillment.order.domain.ReservationStatus;
import com.why.fulfillment.order.service.OrderApplicationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** SQL contract tests only: no database or schema mutation is performed by this class. */
class OrderReservationRecoveryRepositoryTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OrderRepository repository = new OrderRepository(jdbc);

    @Test
    void scanUsesDatabaseAgeAndExcludesEveryRedisCommandState() {
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(OrderStatus.PENDING_PAYMENT.code()),
                eq(ReservationStatus.RESERVING.code()), eq(60L), eq(50))).thenReturn(List.of(10L));

        assertThat(repository.findStaleReservingOrderIds(60L, 50)).containsExactly(10L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), eq(Long.class), eq(OrderStatus.PENDING_PAYMENT.code()),
                eq(ReservationStatus.RESERVING.code()), eq(60L), eq(50));
        assertStalePredicates(sql.getValue());
        assertThat(compact(sql.getValue())).contains("ORDER BY o.update_time, o.order_id LIMIT ?");
    }

    @Test
    void staleCasRepeatsAllScanGuardsAndWritesOnlyCancellationAndPendingCompensation() {
        when(jdbc.update(anyString(), eq(OrderStatus.CANCELED.code()), eq(ReservationStatus.PENDING_COMPENSATION.code()),
                eq(10L), eq(OrderStatus.PENDING_PAYMENT.code()), eq(ReservationStatus.RESERVING.code()), eq(60L))).thenReturn(1);

        assertThat(repository.markStaleReservingForCompensation(10L, 60L)).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(OrderStatus.CANCELED.code()), eq(ReservationStatus.PENDING_COMPENSATION.code()),
                eq(10L), eq(OrderStatus.PENDING_PAYMENT.code()), eq(ReservationStatus.RESERVING.code()), eq(60L));
        assertStalePredicates(sql.getValue());
        assertThat(compact(sql.getValue())).contains("WHERE o.order_id = ?", "SET o.status = ?, o.reservation_status = ?");
    }

    @Test
    void aLostStaleCasReturnsFalseRatherThanClaimingRecovery() {
        assertThat(repository.markStaleReservingForCompensation(10L, 60L)).isFalse();
    }

    @Test
    void requestCompensationDecisionCannotOverwriteReservedPaidOrCanceledOrders() {
        when(jdbc.update(anyString(), eq(OrderStatus.CANCELED.code()), eq(ReservationStatus.PENDING_COMPENSATION.code()),
                eq("outcome unknown"), eq(10L), eq(OrderStatus.PENDING_PAYMENT.code()), eq(ReservationStatus.RESERVING.code()))).thenReturn(1);

        assertThat(repository.markReservingForCompensation(10L, "outcome unknown")).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(OrderStatus.CANCELED.code()), eq(ReservationStatus.PENDING_COMPENSATION.code()),
                eq("outcome unknown"), eq(10L), eq(OrderStatus.PENDING_PAYMENT.code()), eq(ReservationStatus.RESERVING.code()));
        assertThat(compact(sql.getValue())).contains("WHERE order_id = ? AND status = ? AND reservation_status = ?");
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, 604801L})
    void invalidGraceDoesNotReachSql(long graceSeconds) {
        assertThatThrownBy(() -> repository.findStaleReservingOrderIds(graceSeconds, 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.markStaleReservingForCompensation(10L, graceSeconds))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 501})
    void unboundedScanIsRejected(int limit) {
        assertThatThrownBy(() -> repository.findStaleReservingOrderIds(60L, limit))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void cancellationIsLocalTransactionalButRecoveryOrchestrationDoesNotWrapFeign() throws Exception {
        assertThat(OrderRepository.class.getMethod("markReservingForCompensation", long.class, String.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(OrderRepository.class.getMethod("markStaleReservingForCompensation", long.class, long.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(OrderApplicationService.class.getAnnotation(Transactional.class)).isNull();
        assertThat(OrderApplicationService.class.getMethod("recoverStaleReservation", long.class, long.class)
                .getAnnotation(Transactional.class)).isNull();
    }

    private static void assertStalePredicates(String sql) {
        assertThat(compact(sql)).contains("o.status = ? AND o.reservation_status = ?",
                "o.update_time <= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL ? SECOND)",
                "NOT EXISTS ( SELECT 1 FROM microservice_order_command c WHERE c.order_id = o.order_id )")
                .doesNotContain("c.status");
    }

    private static String compact(String sql) {
        return sql.replaceAll("\\s+", " ").strip();
    }
}
