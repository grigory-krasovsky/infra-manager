package com.example.inframanager.event;

import java.util.List;

import com.example.inframanager.work.WorkerProperties;
import org.springframework.stereotype.Component;

/**
 * Разбирает очередь входящих событий. Расписание задаёт
 * {@link com.example.inframanager.work.SchedulingConfig}; тесты вызывают
 * {@link #runOnce()} напрямую.
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

    /** @return сколько событий этот проход взял в работу */
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
