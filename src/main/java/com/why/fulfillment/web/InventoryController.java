package com.why.fulfillment.web;

import com.why.fulfillment.inventory.entity.SkuStock;
import com.why.fulfillment.inventory.mapper.SkuStockMapper;
import com.why.fulfillment.inventory.reconciliation.StockReconciliationReport;
import com.why.fulfillment.inventory.reconciliation.StockReconciliationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final SkuStockMapper stockMapper;
    private final StockReconciliationService reconciliationService;

    public InventoryController(SkuStockMapper stockMapper,
                               StockReconciliationService reconciliationService) {
        this.stockMapper = stockMapper;
        this.reconciliationService = reconciliationService;
    }

    @GetMapping("/skus/{skuId}")
    public ResponseEntity<ApiResponse<SkuStock>> getStock(@PathVariable Long skuId) {
        if (skuId == null || skuId <= 0) {
            throw new IllegalArgumentException("skuId must be positive");
        }
        SkuStock stock = stockMapper.selectById(skuId);
        return stock == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(ApiResponse.ok(stock));
    }

    @GetMapping("/reconciliation")
    public ApiResponse<StockReconciliationReport> reconcile() {
        return ApiResponse.ok(reconciliationService.inspect());
    }
}
