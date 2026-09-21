package com.why.fulfillment.inventory.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InventoryReservationFenceMapper {
    int ACTIVE = 1;
    int CANCELED = 2;
    int CONFIRMED = 3;

    @Insert("insert into inventory_reservation_fence(order_id, status) values(#{orderId}, 1) "
            + "on duplicate key update order_id = values(order_id)")
    int ensureExists(@Param("orderId") Long orderId);

    @Select("select status from inventory_reservation_fence where order_id = #{orderId} for update")
    Integer selectStatusForUpdate(@Param("orderId") Long orderId);

    @Update("update inventory_reservation_fence set status = #{status} where order_id = #{orderId}")
    int updateStatus(@Param("orderId") Long orderId, @Param("status") int status);
}
