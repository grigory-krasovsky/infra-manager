package com.example.inframanager.event;

/**
 * Turns a stored webhook delivery into whatever it means -- a queued Telegram
 * message, a Trello card move. Implementations arrive with their phases:
 * Bamboo in phase 3, Bitbucket in phase 4.
 *
 * <p>Throwing signals a retryable failure; the worker applies backoff and gives
 * up after {@code maxAttempts}.
 */
public interface InboundEventHandler {

    boolean supports(EventSource source);

    void handle(InboundEvent event);
}
