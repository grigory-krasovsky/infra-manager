package com.example.inframanager.outbound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Entry point for everything that wants to call Trello or Telegram. Nothing calls
 * those APIs directly; it all goes through the queue.
 */
@Service
public class OutboundTaskService {

    private static final Logger log = LoggerFactory.getLogger(OutboundTaskService.class);

    private final OutboundTaskRepository repository;

    public OutboundTaskService(OutboundTaskRepository repository) {
        this.repository = repository;
    }

    /**
     * @param dedupKey must be derived from the triggering fact, not from the current
     *                 time, or retries of the same event will enqueue duplicates
     * @return true if the task was queued, false if an identical one already was
     */
    @Transactional
    public boolean enqueue(OutboundTarget target, String action, String dedupKey, String payload) {
        boolean queued = repository.insertIfAbsent(target.name(), action, dedupKey, payload) == 1;
        if (queued) {
            log.debug("Queued {} {} ({})", target, action, dedupKey);
        } else {
            log.debug("Skipped already-queued {} {} ({})", target, action, dedupKey);
        }
        return queued;
    }
}
