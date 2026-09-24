package com.why.fulfillment.commerce.common;

/**
 * 统一错误响应体。
 *
 * @param code    稳定错误码，例如 SPU_NOT_FOUND
 * @param message 面向用户的描述
 */
public record ApiError(String code, String message) {
}
