package com.why.fulfillment.commerce.checkout.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 提交结算。
 *
 * <p>没有商品列表字段：结算的内容是「购物车里已勾选的条目」，由服务端自己读。
 * 让前端把要买的东西连同价格一起传上来，等于让客户端决定付多少钱。</p>
 *
 * @param expectedAmount 用户在结算页看到的合计金额。服务端会重新算一遍，对不上就拒绝。
 *                       这不是信任前端的算术，恰恰相反——它是唯一能发现
 *                       「用户看到的价格和当前价格已经不一样」的办法。少了它，
 *                       改价之后用户会按新价格被扣款，而他同意的是旧价格。
 */
public record CheckoutSubmitRequest(
        @NotNull(message = "expectedAmount 不能为空")
        @DecimalMin(value = "0.00", message = "expectedAmount 不能为负")
        @Digits(integer = 10, fraction = 2, message = "金额最多两位小数")
        BigDecimal expectedAmount) {
}
