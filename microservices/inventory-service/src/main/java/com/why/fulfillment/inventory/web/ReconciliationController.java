package com.why.fulfillment.inventory.web;

import com.why.fulfillment.inventory.reconciliation.StockReconciliationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inventory/reconciliation")
public class ReconciliationController {

    private final StockReconciliationService service;

    public ReconciliationController(StockReconciliationService service) {
        this.service = service;
    }

    @GetMapping
    public StockReconciliationService.ReconciliationReport inspect() {
        return service.inspect();
    }
}
