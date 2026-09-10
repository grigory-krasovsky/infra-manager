package com.example.inframanager.outbound;

/**
 * Performs the actual API call for one target. Telegram arrives in phase 2,
 * Trello in phase 4.
 *
 * <p>Throwing signals a retryable failure. Throw
 * {@link com.example.inframanager.work.RetryAfterException} when the API returned
 * 429 so the worker waits the interval the API asked for rather than guessing.
 */
public interface OutboundTaskSender {

    OutboundTarget target();

    void send(OutboundTask task);
}
