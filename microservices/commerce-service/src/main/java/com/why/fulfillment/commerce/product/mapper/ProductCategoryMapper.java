package com.why.fulfillment.commerce.product.mapper;

import com.why.fulfillment.commerce.product.entity.ProductCategory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProductCategoryMapper {

    @Select("""
            select id, parent_id, name, sort_order, status
            from product_category
            where status = 1
            order by parent_id, sort_order, id
            """)
    List<ProductCategory> selectVisible();
}
