package com.why.fulfillment.commerce.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 *
 * <p>密码长度下限 8 位，上限 72 是 BCrypt 的硬限制——超过 72 字节的部分会被
 * 直接忽略，不拦住的话用户会以为自己设了个长密码。</p>
 */
public record RegisterRequest(
        @NotBlank(message = "不能为空")
        @Size(min = 3, max = 32, message = "长度需在 3-32 之间")
        @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "只允许字母、数字、下划线和连字符")
        String username,

        @NotBlank(message = "不能为空")
        @Size(min = 8, max = 72, message = "长度需在 8-72 之间")
        String password) {
}
