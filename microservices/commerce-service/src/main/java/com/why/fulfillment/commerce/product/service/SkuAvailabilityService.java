package com.why.fulfillment.commerce.product.service;

import com.why.fulfillment.api.inventory.InventoryClient;
import com.why.fulfillment.api.inventory.InventoryQueryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可售库存查询的薄封装。
 *
 * <p>存在的唯一理由是**隔离失败**：Inventory 挂了不应该让商品页 500。
 * 查不到就返回 null，让上层把「库存未知」和「库存为 0」区分开。</p>
 *
 * <p>MVP 的已知缺陷，写在这里避免以后当成设计意图：Inventory 目前只有
 * 单 SKU 查询接口，所以详情页有几个 SKU 就发几次 HTTP。SKU 数量个位数时
 * 可以接受，做商品列表的实时库存展示就必须先给 Inventory 加批量查询接口。
 * 这也是列表页不展示库存的原因。</p>
 */
@Service
public class SkuAvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(SkuAvailabilityService.class);

    private final InventoryClient inventoryClient;

    public SkuAvailabilityService(InventoryClient inventoryClient) {
        this.inventoryClient = inventoryClient;
    }

    /** @return 可售数量；SKU 不存在或 Inventory 不可达时返回 null */
    public Integer available(Long skuId) {
        if (skuId == null) {
            return null;
        }
        try {
            InventoryQueryResponse response = inventoryClient.query(skuId);
            return response == null ? null : response.stock();
        } catch (RuntimeException exception) {
            log.warn("Inventory query failed for sku {}: {}", skuId, exception.toString());
            return null;
        }
    }

    /** 批量查询，保持入参顺序。内部仍是逐个调用，见类注释。 */
    public Map<Long, Integer> available(Collection<Long> skuIds) {
        Map<Long, Integer> result = new LinkedHashMap<>();
        for (Long skuId : skuIds) {
            result.put(skuId, available(skuId));
        }
        return result;
    }
}
