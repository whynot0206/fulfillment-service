package com.why.fulfillment.inventory.service.impl;

import com.why.fulfillment.inventory.entity.SkuStock;
import com.why.fulfillment.inventory.mapper.SkuStockMapper;
import com.why.fulfillment.inventory.service.StockService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockServiceImpl implements StockService {

    private final SkuStockMapper skuStockMapper;

    public StockServiceImpl(SkuStockMapper skuStockMapper) {
        this.skuStockMapper = skuStockMapper;
    }

    @Override
    public boolean deductNaive(Long skuId, int count) {
        if (count <= 0) {
            return false;
        }
        SkuStock current = skuStockMapper.selectById(skuId);
        if (current == null || current.getStock() < count) {
            return false;
        }
        return skuStockMapper.updateStockAbsolute(
                skuId,
                current.getStock() - count,
                current.getLockStock() + count) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deductNaiveInTransaction(Long skuId, int count) {
        if (count <= 0) {
            return false;
        }
        SkuStock current = skuStockMapper.selectById(skuId);
        if (current == null || current.getStock() < count) {
            return false;
        }
        return skuStockMapper.updateStockAbsolute(
                skuId,
                current.getStock() - count,
                current.getLockStock() + count) == 1;
    }

    @Override
    public boolean deductAtomic(Long skuId, int count) {
        return count > 0 && skuStockMapper.reduceStockAtomic(skuId, count) == 1;
    }
}
