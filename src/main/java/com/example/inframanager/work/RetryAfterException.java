package com.example.inframanager.work;

import java.time.Duration;

/**
 * Thrown when a remote API told us to slow down (HTTP 429, usually with a
 * {@code Retry-After} header). Carries the delay so the worker can honour what the
 * API asked for instead of applying its own exponential backoff -- both Trello and
 * Telegram will keep refusing until their window passes.
 */
public class RetryAfterException extends RuntimeException {

    private final Duration retryAfter;

    public RetryAfterException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
