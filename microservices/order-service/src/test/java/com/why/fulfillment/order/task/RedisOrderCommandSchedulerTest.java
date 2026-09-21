package com.why.fulfillment.order.task;

import com.why.fulfillment.order.domain.RedisOrderCommand;
import com.why.fulfillment.order.repository.RedisOrderCommandRepository;
import com.why.fulfillment.order.service.RedisOrderApplicationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisOrderCommandSchedulerTest {

    private final RedisOrderCommandRepository repository = mock(RedisOrderCommandRepository.class);
    private final RedisOrderApplicationService service = mock(RedisOrderApplicationService.class);
    private final RedisOrderCommandScheduler scheduler = new RedisOrderCommandScheduler(repository, service);

    @Test
    void exhaustedPreparationWritesCancellationTombstoneBeforeDeadLetter() {
        RedisOrderCommand command = new RedisOrderCommand(1L, 10L, 20L, new BigDecimal("10.00"),
                30L, "[]", RedisOrderCommand.PREPARING, false, 5,
                LocalDateTime.now(), null, null, "timeout");
        when(repository.findPreparing(50)).thenReturn(List.of(command));
        when(service.compensate(command)).thenReturn(true);

        scheduler.recoverPreparingCommands();

        verify(service).compensate(command);
        verify(repository).markDead(1L, RedisOrderCommand.PREPARING, null,
                "Redis reservation could not be confirmed; cancellation tombstone recorded");
    }

    @Test
    void claimedReadyCommandIsProcessedWithLeaseOwner() {
        RedisOrderCommand command = new RedisOrderCommand(1L, 10L, 20L, new BigDecimal("10.00"),
                30L, "[]", RedisOrderCommand.READY, true, 0,
                LocalDateTime.now(), null, null, null);
        when(repository.findReady(50)).thenReturn(List.of(command));
        when(repository.claim(org.mockito.ArgumentMatchers.eq(1L), anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(true);

        scheduler.persistReadyCommands();

        verify(service).processReady(org.mockito.ArgumentMatchers.eq(command), anyString());
    }

    @Test
    void projectionPendingSchedulesRetryWithoutCompensationEvenAtRetryLimit() {
        RedisOrderCommand command = new RedisOrderCommand(1L, 10L, 20L, new BigDecimal("10.00"),
                30L, "[]", RedisOrderCommand.READY, true, 4,
                LocalDateTime.now(), null, null, null);
        when(repository.findReady(50)).thenReturn(List.of(command));
        when(repository.claim(eq(1L), anyString(), any())).thenReturn(true);
        doThrow(new RedisOrderApplicationService.RedisProjectionPendingException(
                "order was persisted; Redis reservation projection is pending",
                new IllegalStateException("materialize unavailable")))
                .when(service).processReady(eq(command), anyString());

        scheduler.persistReadyCommands();

        verify(repository).scheduleRetry(eq(1L), anyString(), eq(5), any(),
                contains("Redis reservation projection is pending"));
        verify(service, never()).compensate(command);
    }

    @Test
    void unknownReadyFailureNeverCompensatesBecauseOrderMayAlreadyExist() {
        RedisOrderCommand command = new RedisOrderCommand(1L, 10L, 20L, new BigDecimal("10.00"),
                30L, "[]", RedisOrderCommand.READY, true, 9,
                LocalDateTime.now(), null, null, null);
        when(repository.findReady(50)).thenReturn(List.of(command));
        when(repository.claim(eq(1L), anyString(), any())).thenReturn(true);
        doThrow(new IllegalStateException("order database unavailable"))
                .when(service).processReady(eq(command), anyString());

        scheduler.persistReadyCommands();

        verify(repository).scheduleRetry(eq(1L), anyString(), eq(10), any(),
                contains("order database unavailable"));
        verify(service, never()).compensate(command);
        verify(repository, never()).markDead(eq(1L), eq(RedisOrderCommand.PROCESSING),
                anyString(), anyString());
    }
}
