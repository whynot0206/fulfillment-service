package com.why.fulfillment.order.task;

import com.why.fulfillment.order.repository.OrderRepository;
import com.why.fulfillment.order.service.OrderApplicationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderReservationRecoverySchedulerTest {

    private final OrderRepository repository = mock(OrderRepository.class);
    private final OrderApplicationService service = mock(OrderApplicationService.class);

    @Test
    void passesTheConfiguredGraceToBothScanAndRecoveryDecision() {
        OrderReservationRecoveryScheduler scheduler = new OrderReservationRecoveryScheduler(repository, service, 17L);
        when(repository.findStaleReservingOrderIds(17L, 50)).thenReturn(List.of(10L, 11L));

        scheduler.recoverStaleReservations();

        verify(repository).findStaleReservingOrderIds(17L, 50);
        verify(service).recoverStaleReservation(10L, 17L);
        verify(service).recoverStaleReservation(11L, 17L);
    }

    @Test
    void emptyScanDoesNotAttemptAnyRelease() {
        OrderReservationRecoveryScheduler scheduler = new OrderReservationRecoveryScheduler(repository, service, 60L);
        when(repository.findStaleReservingOrderIds(60L, 50)).thenReturn(List.of());

        scheduler.recoverStaleReservations();

        verifyNoInteractions(service);
    }

    @Test
    void oneFailedRecoveryDoesNotPreventOtherCandidatesFromBeingAttempted() {
        OrderReservationRecoveryScheduler scheduler = new OrderReservationRecoveryScheduler(repository, service, 60L);
        when(repository.findStaleReservingOrderIds(60L, 50)).thenReturn(List.of(10L, 11L));
        when(service.recoverStaleReservation(10L, 60L)).thenThrow(new IllegalStateException("database unavailable"));

        scheduler.recoverStaleReservations();

        verify(service).recoverStaleReservation(11L, 60L);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, 604801L})
    void invalidGraceFailsBeforeScheduling(long graceSeconds) {
        assertThatThrownBy(() -> new OrderReservationRecoveryScheduler(repository, service, graceSeconds))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, service);
    }
}
