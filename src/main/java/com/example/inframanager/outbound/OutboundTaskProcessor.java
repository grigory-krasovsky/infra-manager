package com.example.inframanager.outbound;

import java.time.Instant;
import java.util.List;

import com.example.inframanager.work.ProcessingStatus;
import com.example.inframanager.work.RetryAfterException;
import com.example.inframanager.work.RetryPolicy;
import com.example.inframanager.work.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends exactly one task per transaction.
 *
 * <p>The row lock is held across the HTTP call. At our volumes that costs nothing
 * and it is what keeps a second worker from sending the same message. Delivery is
 * at-least-once: a crash between a successful send and the commit re-sends. The
 * alternative -- marking sent before sending -- loses messages instead, which is
 * worse for a deployment notification.
 */
@Component
public class OutboundTaskProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboundTaskProcessor.class);
    private static final int MAX_ERROR_LENGTH = 4000;

    private final OutboundTaskRepository repository;
    private final List<OutboundTaskSender> senders;
    private final WorkerProperties.Settings settings;

    public OutboundTaskProcessor(OutboundTaskRepository repository,
                                 List<OutboundTaskSender> senders,
                                 WorkerProperties properties) {
        // One sender per target, for the same reason handlers are one per source.
        for (OutboundTarget target : OutboundTarget.values()) {
            List<OutboundTaskSender> claiming = senders.stream().filter(s -> s.target() == target).toList();
            if (claiming.size() > 1) {
                throw new IllegalStateException(
                        "More than one OutboundTaskSender claims " + target + ": " + claiming);
            }
        }
        this.repository = repository;
        this.senders = senders;
        this.settings = properties.outbound();
    }

    /** @return true if this call took ownership of the row */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean processOne(long id) {
        OutboundTask task = repository.lockClaimable(id).orElse(null);
        if (task == null) {
            return false;
        }

        task.setAttempts(task.getAttempts() + 1);

        OutboundTaskSender sender = senders.stream()
                .filter(s -> s.target() == task.getTarget())
                .findFirst()
                .orElse(null);

        if (sender == null) {
            log.warn("No sender for {} task {} ({}); skipping",
                    task.getTarget(), task.getId(), task.getAction());
            task.setStatus(ProcessingStatus.SKIPPED);
            task.setLastError("no sender registered for target " + task.getTarget());
            task.setCompletedAt(Instant.now());
            return true;
        }

        try {
            sender.send(task);
            task.setStatus(ProcessingStatus.DONE);
            task.setLastError(null);
            task.setCompletedAt(Instant.now());
        } catch (RetryAfterException e) {
            // Not counted as a real attempt: the API refused to look at the request
            // at all, so burning the retry budget on it would drop valid messages.
            task.setAttempts(task.getAttempts() - 1);
            task.setLastError(String.valueOf(e));
            task.setNextAttemptAt(Instant.now().plus(e.getRetryAfter()));
            log.warn("{} rate limited task {}; retrying in {}",
                    task.getTarget(), task.getId(), e.getRetryAfter());
        } catch (Exception e) {
            recordFailure(task, e);
        }
        return true;
    }

    private void recordFailure(OutboundTask task, Exception e) {
        String message = String.valueOf(e);
        task.setLastError(message.length() > MAX_ERROR_LENGTH
                ? message.substring(0, MAX_ERROR_LENGTH)
                : message);

        if (task.getAttempts() >= settings.maxAttempts()) {
            log.error("Giving up on {} task {} after {} attempts",
                    task.getTarget(), task.getId(), task.getAttempts(), e);
            task.setStatus(ProcessingStatus.FAILED);
            task.setCompletedAt(Instant.now());
        } else {
            Instant next = RetryPolicy.nextAttemptAt(
                    Instant.now(), task.getAttempts(), settings.baseBackoff(), settings.maxBackoff());
            log.warn("Attempt {} failed for {} task {}; retrying at {}",
                    task.getAttempts(), task.getTarget(), task.getId(), next, e);
            task.setNextAttemptAt(next);
        }
    }
}
