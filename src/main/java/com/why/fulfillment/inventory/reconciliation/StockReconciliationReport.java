package com.why.fulfillment.inventory.reconciliation;

import java.time.LocalDateTime;
import java.util.List;

public record StockReconciliationReport(
        LocalDateTime checkedAt,
        int checkedSkuCount,
        List<StockReconciliationItem> differences,
        List<String> errors) {

    public boolean consistent() {
        return differences.isEmpty() && errors.isEmpty();
    }
}
