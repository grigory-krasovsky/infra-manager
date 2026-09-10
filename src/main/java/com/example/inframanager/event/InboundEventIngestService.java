package com.example.inframanager.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Единственное, что позволено контроллеру вебхука: записать доставку и вернуть
 * ответ. Разбирать её — работа воркера, иначе медленный или сломанный Trello
 * заставит нас выйти за таймаут и потерять вебхук с ограниченным числом повторов.
 */
@Service
public class InboundEventIngestService {

    private static final Logger log = LoggerFactory.getLogger(InboundEventIngestService.class);

    private final InboundEventRepository repository;

    public InboundEventIngestService(InboundEventRepository repository) {
        this.repository = repository;
    }

    /**
     * @param externalId устойчив в пределах одной доставки; повторная доставка того же
     *                   идентификатора игнорируется
     * @param payload    сырое тело запроса, без изменений
     * @return true, если доставка новая, false — если дубликат
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
