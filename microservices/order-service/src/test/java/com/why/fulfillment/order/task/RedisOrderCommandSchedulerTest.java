package com.why.fulfillment.order.task;

import com.why.fulfillment.order.domain.RedisOrderCommand;
import com.why.fulfillment.order.repository.RedisOrderCommandRepository;
import com.why.fulfillment.order.service.RedisOrderApplicationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
}
