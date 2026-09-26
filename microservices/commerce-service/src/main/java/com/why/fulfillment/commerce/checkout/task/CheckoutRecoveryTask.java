package com.why.fulfillment.commerce.checkout.task;

import com.why.fulfillment.commerce.checkout.mapper.CheckoutRequestMapper;
import com.why.fulfillment.commerce.checkout.service.CheckoutRecoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded scan; manual-review records require explicit same-key read-only rechecks. */
@Component
public class CheckoutRecoveryTask {
    private static final Logger log = LoggerFactory.getLogger(CheckoutRecoveryTask.class);
    private final CheckoutRequestMapper mapper;
    private final CheckoutRecoveryService recovery;
    private final boolean enabled;
    private final int batchSize;

    public CheckoutRecoveryTask(CheckoutRequestMapper mapper, CheckoutRecoveryService recovery,
            @Value("${commerce.checkout.recovery.enabled:true}") boolean enabled,
            @Value("${commerce.checkout.recovery.batch-size:20}") int batchSize) {
        this.mapper = mapper;
        this.recovery = recovery;
        this.enabled = enabled;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
    }

    @Scheduled(fixedDelayString = "${commerce.checkout.recovery.poll-delay-ms:1000}")
    public void recover() {
        if (!enabled) {
            return;
        }
        for (Long id : mapper.findReadyIds(batchSize)) {
            try {
                recovery.recover(id);
            } catch (RuntimeException exception) {
                log.warn("Checkout recovery attempt failed for intent {} ({})", id,
                        exception.getClass().getSimpleName());
            }
        }
    }
}
