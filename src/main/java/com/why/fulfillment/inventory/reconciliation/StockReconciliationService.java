package com.why.fulfillment.inventory.reconciliation;

public interface StockReconciliationService {

    /** Builds a report only. Corrections require a separate, reviewed action. */
    StockReconciliationReport inspect();
}
