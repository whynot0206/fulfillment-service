package com.why.fulfillment.inventory.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically emits the inventory difference list for operators and local verification. */
@Component
@ConditionalOnProperty(
        name = {"fulfillment.scheduling.enabled", "fulfillment.inventory.reconciliation.enabled"},
        havingValue = "true",
        matchIfMissing = true)
public class StockReconciliationTask {

    private static final Logger log = LoggerFactory.getLogger(StockReconciliationTask.class);

    private final StockReconciliationService reconciliationService;

    public StockReconciliationTask(StockReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(
            initialDelayString = "${fulfillment.inventory.reconciliation.initial-delay-ms:30000}",
            fixedDelayString = "${fulfillment.inventory.reconciliation.fixed-delay-ms:60000}")
    public void inspect() {
        StockReconciliationReport report = reconciliationService.inspect();
        if (report.consistent()) {
            log.info("Stock reconciliation completed: checkedSkus={}, differences=0",
                    report.checkedSkuCount());
            return;
        }
        log.warn("Stock reconciliation differences: checkedSkus={}, differences={}, errors={}",
                report.checkedSkuCount(), report.differences(), report.errors());
    }
}
