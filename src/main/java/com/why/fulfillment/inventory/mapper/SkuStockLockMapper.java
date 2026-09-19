package com.why.fulfillment.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.why.fulfillment.inventory.entity.SkuStockLock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SkuStockLockMapper extends BaseMapper<SkuStockLock> {

    List<SkuStockLock> selectByOrderId(@Param("orderId") Long orderId);

    int markReleasedIfLocked(@Param("id") Long id);

    int markConfirmedIfLocked(@Param("id") Long id);
}
