package com.why.fulfillment.order.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class OrderApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    /**
     * A missing {@code X-User-Id} means the request did not come through the gateway, or came
     * through without a token. That is an authentication problem, so it answers 401 rather
     * than Spring's default 400 — a 400 would send the frontend into "fix your request"
     * handling when what it actually needs to do is send the user to the login page.
     *
     * <p>The message names the header on purpose: it is not a secret, and a caller debugging a
     * direct curl against 18081 otherwise has nothing to go on.</p>
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail missingIdentity(MissingRequestHeaderException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "missing " + exception.getHeaderName() + "; this endpoint must be called through the gateway");
    }
}
