package com.why.fulfillment.ratelimit;

/** Raised when one of the configured token buckets has no available token. */
public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException() {
        super("rate limit exceeded");
    }

    public RateLimitExceededException(String message) {
        super(message);
    }
}
