package com.why.fulfillment.commerce.cart.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AddToCartRequest(
        @NotNull(message = "不能为空") Long skuId,
        @NotNull(message = "不能为空")
        @Min(value = 1, message = "至少 1 件")
        @Max(value = 200, message = "单次最多 200 件")
        Integer quantity) {
}
