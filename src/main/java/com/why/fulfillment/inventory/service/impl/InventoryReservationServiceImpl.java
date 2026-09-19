package com.why.fulfillment.inventory.service.impl;

import com.why.fulfillment.inventory.entity.SkuStockLock;
import com.why.fulfillment.inventory.mapper.SkuStockLockMapper;
import com.why.fulfillment.inventory.mapper.SkuStockMapper;
import com.why.fulfillment.inventory.service.InventoryReservationService;
import com.why.fulfillment.inventory.service.StockReservationItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class InventoryReservationServiceImpl implements InventoryReservationService {

    private final SkuStockMapper skuStockMapper;
    private final SkuStockLockMapper skuStockLockMapper;

    public InventoryReservationServiceImpl(SkuStockMapper skuStockMapper,
                                           SkuStockLockMapper skuStockLockMapper) {
        this.skuStockMapper = skuStockMapper;
        this.skuStockLockMapper = skuStockLockMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reserve(Long orderId, List<StockReservationItem> items) {
        if (orderId == null || items == null || items.isEmpty()) {
            throw new IllegalArgumentException("orderId and items are required");
        }

        // 合并同一 SKU 后再排序，避免一笔订单重复插入锁定记录或重复申请行锁。
        Map<Long, StockReservationItem> merged = new LinkedHashMap<>();
        for (StockReservationItem item : items) {
            if (item == null || item.skuId() == null || item.spuId() == null || item.count() <= 0) {
                throw new IllegalArgumentException("invalid stock reservation item");
            }
            merged.merge(item.skuId(), item,
                    (left, right) -> new StockReservationItem(
                            left.skuId(), left.spuId(), left.count() + right.count()));
        }

        List<StockReservationItem> ordered = new ArrayList<>(merged.values());
        ordered.sort(Comparator.comparing(StockReservationItem::skuId));
        for (StockReservationItem item : ordered) {
            if (skuStockMapper.reduceStockAtomic(item.skuId(), item.count()) != 1) {
                throw new IllegalStateException("insufficient stock for sku " + item.skuId());
            }
            SkuStockLock lock = new SkuStockLock();
            lock.setOrderId(orderId);
            lock.setSkuId(item.skuId());
            lock.setSpuId(item.spuId());
            lock.setCount(item.count());
            lock.setStatus(SkuStockLock.LOCKED);
            skuStockLockMapper.insert(lock);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void release(Long orderId) {
        for (SkuStockLock lock : skuStockLockMapper.selectByOrderId(orderId)) {
            if (skuStockLockMapper.markReleasedIfLocked(lock.getId()) == 1
                    && skuStockMapper.addAvailableStock(lock.getSkuId(), lock.getCount()) != 1) {
                throw new IllegalStateException("failed to release stock for sku " + lock.getSkuId());
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long orderId) {
        for (SkuStockLock lock : skuStockLockMapper.selectByOrderId(orderId)) {
            if (skuStockLockMapper.markConfirmedIfLocked(lock.getId()) == 1
                    && skuStockMapper.consumeLockedStock(lock.getSkuId(), lock.getCount()) != 1) {
                throw new IllegalStateException("failed to confirm stock for sku " + lock.getSkuId());
            }
        }
    }
}
