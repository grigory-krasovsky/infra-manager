package com.example.inframanager.event;

import java.util.List;

import com.example.inframanager.work.WorkerProperties;
import org.springframework.stereotype.Component;

/**
 * Drains pending inbound events. Scheduled by
 * {@link com.example.inframanager.work.SchedulingConfig}; tests call
 * {@link #runOnce()} directly.
 */
@Component
public class InboundEventWorker {

    private final InboundEventRepository repository;
    private final InboundEventProcessor processor;
    private final WorkerProperties.Settings settings;

    public InboundEventWorker(InboundEventRepository repository,
                              InboundEventProcessor processor,
                              WorkerProperties properties) {
        this.repository = repository;
        this.processor = processor;
        this.settings = properties.inbound();
    }

    /** @return how many events this pass took ownership of */
    public int runOnce() {
        List<Long> ids = repository.findClaimableIds(settings.batchSize());
        int processed = 0;
        for (Long id : ids) {
            if (processor.processOne(id)) {
                processed++;
            }
        }
        return processed;
    }
}
