package com.example.inframanager.event;

import java.time.Instant;
import java.util.List;

import com.example.inframanager.work.ProcessingStatus;
import com.example.inframanager.work.RetryPolicy;
import com.example.inframanager.work.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles exactly one event per transaction.
 *
 * <p>Separate from {@link InboundEventWorker} so the {@code REQUIRES_NEW} boundary
 * actually applies -- a self-invocation inside the worker would bypass the proxy.
 * One transaction per row means a crash mid-handling rolls back to PENDING and the
 * event is simply retried, with no half-finished state and no reaper to write.
 */
@Component
public class InboundEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(InboundEventProcessor.class);
    private static final int MAX_ERROR_LENGTH = 4000;

    private final InboundEventRepository repository;
    private final List<InboundEventHandler> handlers;
    private final WorkerProperties.Settings settings;

    public InboundEventProcessor(InboundEventRepository repository,
                                 List<InboundEventHandler> handlers,
                                 WorkerProperties properties) {
        // A source means one thing, so it gets one handler. Picking the first of
        // several would silently ignore the others depending on bean ordering.
        for (EventSource source : EventSource.values()) {
            List<InboundEventHandler> claiming = handlers.stream().filter(h -> h.supports(source)).toList();
            if (claiming.size() > 1) {
                throw new IllegalStateException(
                        "More than one InboundEventHandler claims " + source + ": " + claiming);
            }
        }
        this.repository = repository;
        this.handlers = handlers;
        this.settings = properties.inbound();
    }

    /** @return true if this call took ownership of the row */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean processOne(long id) {
        InboundEvent event = repository.lockClaimable(id).orElse(null);
        if (event == null) {
            return false;
        }

        event.setAttempts(event.getAttempts() + 1);

        InboundEventHandler handler = handlers.stream()
                .filter(h -> h.supports(event.getSource()))
                .findFirst()
                .orElse(null);

        if (handler == null) {
            // Not an error worth retrying: no amount of waiting registers a handler.
            log.warn("No handler for {} event {} ({}); skipping",
                    event.getSource(), event.getExternalId(), event.getEventType());
            event.setStatus(ProcessingStatus.SKIPPED);
            event.setLastError("no handler registered for source " + event.getSource());
            event.setProcessedAt(Instant.now());
            return true;
        }

        try {
            handler.handle(event);
            event.setStatus(ProcessingStatus.DONE);
            event.setLastError(null);
            event.setProcessedAt(Instant.now());
        } catch (Exception e) {
            recordFailure(event, e);
        }
        return true;
    }

    private void recordFailure(InboundEvent event, Exception e) {
        String message = String.valueOf(e);
        event.setLastError(message.length() > MAX_ERROR_LENGTH
                ? message.substring(0, MAX_ERROR_LENGTH)
                : message);

        if (event.getAttempts() >= settings.maxAttempts()) {
            log.error("Giving up on {} event {} after {} attempts",
                    event.getSource(), event.getExternalId(), event.getAttempts(), e);
            event.setStatus(ProcessingStatus.FAILED);
            event.setProcessedAt(Instant.now());
        } else {
            Instant next = RetryPolicy.nextAttemptAt(
                    Instant.now(), event.getAttempts(), settings.baseBackoff(), settings.maxBackoff());
            log.warn("Attempt {} failed for {} event {}; retrying at {}",
                    event.getAttempts(), event.getSource(), event.getExternalId(), next, e);
            event.setNextAttemptAt(next);
        }
    }
}
