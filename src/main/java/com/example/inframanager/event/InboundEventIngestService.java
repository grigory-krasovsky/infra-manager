package com.example.inframanager.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only thing a webhook controller is allowed to do: record the delivery and
 * return. Interpreting it is the worker's job, so a slow or broken Trello cannot
 * make us time out and lose a webhook that has a limited retry budget.
 */
@Service
public class InboundEventIngestService {

    private static final Logger log = LoggerFactory.getLogger(InboundEventIngestService.class);

    private final InboundEventRepository repository;

    public InboundEventIngestService(InboundEventRepository repository) {
        this.repository = repository;
    }

    /**
     * @param externalId stable per delivery; redelivery of the same id is ignored
     * @param payload    raw request body, unmodified
     * @return true if this was a new delivery, false if it was a duplicate
     */
    @Transactional
    public boolean ingest(EventSource source, String externalId, String eventType, String payload) {
        boolean inserted = repository.insertIfAbsent(source.name(), externalId, eventType, payload) == 1;
        if (inserted) {
            log.debug("Recorded {} event {} ({})", source, externalId, eventType);
        } else {
            log.debug("Ignored duplicate {} event {} ({})", source, externalId, eventType);
        }
        return inserted;
    }
}
