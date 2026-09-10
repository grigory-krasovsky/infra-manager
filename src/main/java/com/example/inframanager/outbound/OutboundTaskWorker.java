package com.example.inframanager.outbound;

import java.util.List;

import com.example.inframanager.work.WorkerProperties;
import org.springframework.stereotype.Component;

/**
 * Разгребает очередь исходящих задач. Планируется в
 * {@link com.example.inframanager.work.SchedulingConfig}; тесты вызывают
 * {@link #runOnce()} напрямую.
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

    /** @return сколько задач этот проход забрал в работу */
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
