package com.why.fulfillment.inventory.reconciliation;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Keeps the latest scheduled report so dashboard polling does not rescan inventory. */
@Component
public class StockReconciliationState {

    private final AtomicReference<StockReconciliationReport> latest = new AtomicReference<>();

    public void update(StockReconciliationReport report) {
        latest.set(report);
    }

    public Optional<StockReconciliationReport> latest() {
        return Optional.ofNullable(latest.get());
    }
}
