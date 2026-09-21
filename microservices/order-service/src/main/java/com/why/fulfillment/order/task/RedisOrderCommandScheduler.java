package com.why.fulfillment.order.task;

import com.why.fulfillment.order.domain.RedisOrderCommand;
import com.why.fulfillment.order.repository.RedisOrderCommandRepository;
import com.why.fulfillment.order.service.RedisOrderApplicationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

@Component
public class RedisOrderCommandScheduler {

    private static final int BATCH_SIZE = 50;
    private static final int MAX_RETRIES = 5;
    private final RedisOrderCommandRepository repository;
    private final RedisOrderApplicationService service;
    private final String leaseOwner = "order-service-" + UUID.randomUUID();

    public RedisOrderCommandScheduler(RedisOrderCommandRepository repository,
                                      RedisOrderApplicationService service) {
        this.repository = repository;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${fulfillment.redis-order.prepare-poll-ms:1000}")
    public void recoverPreparingCommands() {
        repository.findPreparing(BATCH_SIZE).forEach(command -> {
            if (command.retryCount() >= MAX_RETRIES) {
                if (service.compensate(command)) {
                    repository.markDead(command.commandId(), RedisOrderCommand.PREPARING, null,
                            "Redis reservation could not be confirmed; cancellation tombstone recorded");
                } else {
                    repository.retryPreparing(command.commandId(), command.retryCount() + 1,
                            LocalDateTime.now().plusSeconds(60), "Redis compensation is still pending");
                }
                return;
            }
            service.prepare(command, true);
        });
    }

    @Scheduled(fixedDelayString = "${fulfillment.redis-order.persist-poll-ms:500}")
    public void persistReadyCommands() {
        repository.findReady(BATCH_SIZE).forEach(command -> {
            if (!repository.claim(command.commandId(), leaseOwner, LocalDateTime.now().plusSeconds(60))) {
                return;
            }
            try {
                service.processReady(command, leaseOwner);
            } catch (RuntimeException exception) {
                handlePersistenceFailure(command, exception);
            }
        });
    }

    private void handlePersistenceFailure(RedisOrderCommand command, RuntimeException exception) {
        int retry = command.retryCount() + 1;
        if (retry >= MAX_RETRIES && service.compensate(command)) {
            repository.markDead(command.commandId(), RedisOrderCommand.PROCESSING, leaseOwner,
                    "order persistence failed and Redis was compensated: " + safeMessage(exception));
            return;
        }
        repository.scheduleRetry(command.commandId(), leaseOwner, retry,
                LocalDateTime.now().plusSeconds(Math.min(300, 1L << Math.min(8, retry - 1))),
                safeMessage(exception));
    }

    private static String safeMessage(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
