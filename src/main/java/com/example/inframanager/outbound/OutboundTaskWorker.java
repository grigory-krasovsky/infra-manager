package com.example.inframanager.outbound;

import java.util.List;

import com.example.inframanager.work.WorkerProperties;
import org.springframework.stereotype.Component;

/**
 * Drains queued outbound tasks. Scheduled by
 * {@link com.example.inframanager.work.SchedulingConfig}; tests call
 * {@link #runOnce()} directly.
 */
@Component
public class OutboundTaskWorker {

    private final OutboundTaskRepository repository;
    private final OutboundTaskProcessor processor;
    private final WorkerProperties.Settings settings;

    public OutboundTaskWorker(OutboundTaskRepository repository,
                              OutboundTaskProcessor processor,
                              WorkerProperties properties) {
        this.repository = repository;
        this.processor = processor;
        this.settings = properties.outbound();
    }

    /** @return how many tasks this pass took ownership of */
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
