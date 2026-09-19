package com.why.fulfillment.order.task;

import com.why.fulfillment.order.entity.AsyncOrderCommand;
import com.why.fulfillment.order.mapper.AsyncOrderCommandMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Polls, leases and retries durable order persistence commands. */
@Component
@ConditionalOnProperty(
        name = {"fulfillment.scheduling.enabled", "fulfillment.order.async-command.enabled"},
        havingValue = "true",
        matchIfMissing = true)
public class AsyncOrderCommandScheduler {

    private static final Logger log = LoggerFactory.getLogger(AsyncOrderCommandScheduler.class);
    private static final int BATCH_SIZE = 100;
    private static final int MAX_ERROR_LENGTH = 500;

    private final AsyncOrderCommandMapper commandMapper;
    private final AsyncOrderCommandProcessor processor;
    private final int maxRetries;
    private final long initialRetryDelaySeconds;
    private final long maxRetryDelaySeconds;
    private final long leaseSeconds;
    private final String leaseOwner;

    public AsyncOrderCommandScheduler(AsyncOrderCommandMapper commandMapper,
                                      AsyncOrderCommandProcessor processor) {
        this(commandMapper, processor, 5, 5, 300, 300);
    }

    public AsyncOrderCommandScheduler(AsyncOrderCommandMapper commandMapper,
                                      AsyncOrderCommandProcessor processor,
                                      int maxRetries,
                                      long initialRetryDelaySeconds,
                                      long maxRetryDelaySeconds) {
        this(commandMapper, processor, maxRetries, initialRetryDelaySeconds,
                maxRetryDelaySeconds, 300);
    }

    /** Configuration is explicit so focused tests can instantiate the scheduler directly. */
    @Autowired
    public AsyncOrderCommandScheduler(AsyncOrderCommandMapper commandMapper,
                                      AsyncOrderCommandProcessor processor,
                                      @Value("${fulfillment.order.async-command.retry.max-retries:5}") int maxRetries,
                                      @Value("${fulfillment.order.async-command.retry.initial-delay-seconds:5}") long initialRetryDelaySeconds,
                                      @Value("${fulfillment.order.async-command.retry.max-delay-seconds:300}") long maxRetryDelaySeconds,
                                      @Value("${fulfillment.order.async-command.lease-seconds:300}") long leaseSeconds) {
        this.commandMapper = commandMapper;
        this.processor = processor;
        this.maxRetries = Math.max(0, maxRetries);
        this.initialRetryDelaySeconds = Math.max(1, initialRetryDelaySeconds);
        this.maxRetryDelaySeconds = Math.max(this.initialRetryDelaySeconds, maxRetryDelaySeconds);
        this.leaseSeconds = Math.max(1, leaseSeconds);
        this.leaseOwner = "fulfillment-" + UUID.randomUUID();
    }

    @Scheduled(fixedDelayString = "${fulfillment.order.async-command.poll-ms:500}")
    public void processReadyCommands() {
        List<AsyncOrderCommand> commands = commandMapper.listReady(BATCH_SIZE);
        for (AsyncOrderCommand command : commands) {
            processOne(command);
        }
    }

    private void processOne(AsyncOrderCommand command) {
        LocalDateTime leaseUntil = LocalDateTime.now().plusSeconds(leaseSeconds);
        if (commandMapper.claim(command.getCommandId(), leaseOwner, leaseUntil) != 1) {
            return;
        }
        try {
            processor.processClaimed(command, leaseOwner);
        } catch (RuntimeException exception) {
            handleFailure(command, exception);
        }
    }

    private void handleFailure(AsyncOrderCommand command, RuntimeException exception) {
        int retryCount = (command.getRetryCount() == null ? 0 : command.getRetryCount()) + 1;
        String message = failureMessage(exception);
        if (retryCount <= maxRetries) {
            scheduleRetry(command, retryCount, message);
            return;
        }

        // Compensation is deliberately outside the MySQL transaction. The
        // adapter must make this operation idempotent because a process crash
        // can occur between Redis compensation and the dead-letter update.
        try {
            processor.compensate(command);
        } catch (RuntimeException compensationFailure) {
            String compensationMessage = failureMessage(compensationFailure);
            scheduleRetry(command, retryCount,
                    truncate(message + "; redis compensation failed: " + compensationMessage));
            log.error("Redis compensation failed for async order command {}; keeping it retryable",
                    command.getCommandId(), compensationFailure);
            return;
        }

        if (commandMapper.markDead(command.getCommandId(), leaseOwner, message, LocalDateTime.now()) != 1) {
            log.warn("Async order command {} lost its lease before dead-letter update",
                    command.getCommandId());
            return;
        }
        log.error("Async order command {} moved to dead letter after {} retries: {}",
                command.getCommandId(), retryCount, message);
    }

    private void scheduleRetry(AsyncOrderCommand command, int retryCount, String message) {
        LocalDateTime nextRetry = LocalDateTime.now().plusSeconds(retryDelaySeconds(retryCount));
        if (commandMapper.scheduleRetry(command.getCommandId(), leaseOwner, retryCount,
                nextRetry, truncate(message)) != 1) {
            log.warn("Async order command {} lost its lease before retry update",
                    command.getCommandId());
            return;
        }
        log.warn("Async order command {} failed; retry {} scheduled at {}: {}",
                command.getCommandId(), retryCount, nextRetry, message);
    }

    private long retryDelaySeconds(int retryCount) {
        long delay = initialRetryDelaySeconds;
        int doublings = Math.min(Math.max(0, retryCount - 1), 63);
        for (int i = 0; i < doublings && delay < maxRetryDelaySeconds; i++) {
            if (delay > maxRetryDelaySeconds / 2) {
                return maxRetryDelaySeconds;
            }
            delay *= 2;
        }
        return Math.min(delay, maxRetryDelaySeconds);
    }

    private String failureMessage(Throwable exception) {
        String message = exception.getMessage();
        return truncate(message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message);
    }

    private String truncate(String message) {
        return message.substring(0, Math.min(MAX_ERROR_LENGTH, message.length()));
    }
}
