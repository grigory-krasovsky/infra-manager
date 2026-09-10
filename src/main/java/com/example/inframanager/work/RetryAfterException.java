package com.example.inframanager.work;

import java.time.Duration;

/**
 * Бросается, когда внешний API попросил сбавить темп (HTTP 429, обычно с заголовком
 * {@code Retry-After}). Несёт в себе интервал, чтобы воркер выждал именно столько,
 * сколько попросили, а не применял собственную экспоненциальную задержку: и Trello,
 * и Telegram будут отказывать, пока их окно не истечёт.
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
