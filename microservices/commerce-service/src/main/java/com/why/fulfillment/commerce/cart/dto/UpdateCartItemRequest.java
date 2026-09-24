package com.why.fulfillment.commerce.cart.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 直接设定数量（不是增量）。前端的 +/- 和输入框都走这个。 */
public record UpdateCartItemRequest(
        @NotNull(message = "不能为空")
        @Min(value = 1, message = "至少 1 件；要清空请用删除")
        @Max(value = 200, message = "单个规格最多 200 件")
        Integer quantity) {
}
