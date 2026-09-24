package com.why.fulfillment.commerce.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：带上要返回的 HTTP 状态和一个稳定的错误码。
 *
 * <p>错误码是给前端判断用的，消息是给人看的。前端不应该靠消息文案做分支——
 * 文案随时会改，错误码不会。</p>
 */
public class CommerceException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public CommerceException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static CommerceException notFound(String code, String message) {
        return new CommerceException(HttpStatus.NOT_FOUND, code, message);
    }

    public static CommerceException badRequest(String code, String message) {
        return new CommerceException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static CommerceException conflict(String code, String message) {
        return new CommerceException(HttpStatus.CONFLICT, code, message);
    }

    public static CommerceException unauthorized(String code, String message) {
        return new CommerceException(HttpStatus.UNAUTHORIZED, code, message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
