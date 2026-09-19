package com.why.fulfillment.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * SKU 库存（三态模型）。
 * 实际库存 = stock + lockStock（未发货前）。
 */
@TableName("sku_stock")
public class SkuStock {

    @TableId
    private Long skuId;
    private Long spuId;
    /** 可售库存 */
    private Integer stock;
    /** 锁定库存（已预占，未实扣） */
    private Integer lockStock;
    private Integer version;
    private LocalDateTime updateTime;

    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }

    public Long getSpuId() { return spuId; }
    public void setSpuId(Long spuId) { this.spuId = spuId; }

    public Integer getStock() { return stock; }
    public void setStock(Integer stock) { this.stock = stock; }

    public Integer getLockStock() { return lockStock; }
    public void setLockStock(Integer lockStock) { this.lockStock = lockStock; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
