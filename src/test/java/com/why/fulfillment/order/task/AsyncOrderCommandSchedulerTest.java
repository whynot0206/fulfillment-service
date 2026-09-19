package com.why.fulfillment.order.task;

import com.why.fulfillment.order.entity.AsyncOrderCommand;
import com.why.fulfillment.order.mapper.AsyncOrderCommandMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AsyncOrderCommandSchedulerTest {

    @Test
    void failedCommandIsRescheduledWithIncrementedRetryCount() {
        AsyncOrderCommandMapper mapper = mock(AsyncOrderCommandMapper.class);
        AsyncOrderCommandProcessor processor = mock(AsyncOrderCommandProcessor.class);
        AsyncOrderCommand command = command(101L, 0);
        when(mapper.listReady(100)).thenReturn(List.of(command));
        when(mapper.claim(eq(101L), anyString(), any())).thenReturn(1);
        when(mapper.scheduleRetry(eq(101L), anyString(), eq(1), any(), any())).thenReturn(1);
        doThrow(new IllegalStateException("database unavailable"))
                .when(processor).processClaimed(any(), anyString());

        newScheduler(mapper, processor, 2).processReadyCommands();

        verify(mapper).scheduleRetry(eq(101L), anyString(), eq(1), any(), eq("database unavailable"));
        verifyNoInteractionsAfterRetry(processor);
    }

    @Test
    void commandAfterRetryLimitIsCompensatedAndMovedToDeadLetter() {
        AsyncOrderCommandMapper mapper = mock(AsyncOrderCommandMapper.class);
        AsyncOrderCommandProcessor processor = mock(AsyncOrderCommandProcessor.class);
        AsyncOrderCommand command = command(102L, 2);
        when(mapper.listReady(100)).thenReturn(List.of(command));
        when(mapper.claim(eq(102L), anyString(), any())).thenReturn(1);
        when(mapper.markDead(eq(102L), anyString(), eq("database unavailable"), any())).thenReturn(1);
        doThrow(new IllegalStateException("database unavailable"))
                .when(processor).processClaimed(any(), anyString());

        newScheduler(mapper, processor, 2).processReadyCommands();

        verify(processor).compensate(command);
        verify(mapper).markDead(eq(102L), anyString(), eq("database unavailable"), any());
    }

    private AsyncOrderCommand command(long id, int retries) {
        AsyncOrderCommand command = new AsyncOrderCommand();
        command.setCommandId(id);
        command.setOrderId(id);
        command.setRetryCount(retries);
        return command;
    }

    private AsyncOrderCommandScheduler newScheduler(AsyncOrderCommandMapper mapper,
                                                     AsyncOrderCommandProcessor processor,
                                                     int maxRetries) {
        // Constructor values are injected in production; test callers pass
        // the same values explicitly through the @Value-enabled constructor.
        return new AsyncOrderCommandScheduler(mapper, processor, maxRetries, 2, 20, 300);
    }

    private void verifyNoInteractionsAfterRetry(AsyncOrderCommandProcessor processor) {
        // A retry only changes MySQL command state. Redis compensation is
        // intentionally deferred until the dead-letter transition.
        org.mockito.Mockito.verify(processor, org.mockito.Mockito.never()).compensate(any());
    }
}
