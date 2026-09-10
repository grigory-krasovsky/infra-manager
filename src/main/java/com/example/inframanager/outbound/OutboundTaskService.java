package com.example.inframanager.outbound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Точка входа для всего, что хочет обратиться к Trello или Telegram. Напрямую эти API
 * не вызывает никто — всё идёт через очередь.
 */
@Service
public class OutboundTaskService {

    private static final Logger log = LoggerFactory.getLogger(OutboundTaskService.class);

    private final OutboundTaskRepository repository;

    public OutboundTaskService(OutboundTaskRepository repository) {
        this.repository = repository;
    }

    /**
     * @param dedupKey должен выводиться из породившего задачу факта, а не из текущего
     *                 времени, иначе повторы одного события наплодят дубликаты
     * @return true, если задача поставлена в очередь; false, если такая уже была
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
