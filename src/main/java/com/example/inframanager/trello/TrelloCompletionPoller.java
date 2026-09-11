package com.example.inframanager.trello;

import java.time.Instant;

import com.example.inframanager.outbound.OutboundTarget;
import com.example.inframanager.outbound.OutboundTaskService;
import com.example.inframanager.pullrequest.LifecycleProperties;
import com.example.inframanager.pullrequest.PrCardLink;
import com.example.inframanager.pullrequest.PrCardLinkRepository;
import com.example.inframanager.pullrequest.PullRequestRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Отмечает выполненными карточки, которые давно лежат в колонке влитых пул-реквестов.
 *
 * <p>Это опрос, а не обработчик события, потому что события тут и нет: «прошла неделя»
 * никто не присылает, это можно только заметить самому. Карточка при этом остаётся в
 * своей колонке — отметка говорит «здесь всё закончено», а колонка по-прежнему говорит,
 * чем именно.
 *
 * <p>Колонка берётся из отображения {@code pr:merged}: колонка влитых — ровно та, куда
 * это событие кладёт карточку, и заводить вторую настройку с тем же смыслом значило бы
 * дать им однажды разойтись.
 */
public class TrelloCompletionPoller {

    private static final Logger log = LoggerFactory.getLogger(TrelloCompletionPoller.class);
    private static final String MERGED_EVENT = "pr:merged";

    private final TrelloListResolver listResolver;
    private final LifecycleProperties lifecycle;
    private final TrelloProperties properties;
    private final PrCardLinkRepository linkRepository;
    private final OutboundTaskService taskService;
    private final ObjectMapper objectMapper;

    public TrelloCompletionPoller(TrelloListResolver listResolver,
                                  LifecycleProperties lifecycle,
                                  TrelloProperties properties,
                                  PrCardLinkRepository linkRepository,
                                  OutboundTaskService taskService,
                                  ObjectMapper objectMapper) {
        this.listResolver = listResolver;
        this.lifecycle = lifecycle;
        this.properties = properties;
        this.linkRepository = linkRepository;
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    /** @return сколько карточек этот проход поставил в очередь на отметку */
    @Transactional
    public int runOnce() {
        String mergedList = lifecycle.listFor(MERGED_EVENT).orElse(null);
        if (mergedList == null) {
            // Без этой колонки завершаться нечему: доска не знает, что считать влитым.
            log.debug("No list is mapped to {}; nothing can age into 'complete'", MERGED_EVENT);
            return 0;
        }

        Instant enteredBefore = Instant.now().minus(properties.completion().after());
        int queued = 0;
        for (PrCardLink link : linkRepository.findSettledBefore(enteredBefore)) {
            try {
                if (sitsIn(link, mergedList) && enqueue(link, mergedList)) {
                    queued++;
                }
            } catch (Exception e) {
                // Одна проблемная карточка не должна прерывать весь обход.
                log.warn("Could not mark card {} for {}/{}/{} complete", link.getTrelloCardId(),
                        link.getProjectKey(), link.getRepoSlug(), link.getPrId(), e);
            }
        }
        return queued;
    }

    private boolean sitsIn(PrCardLink link, String listName) {
        return listResolver.listId(link.getTrelloBoardId(), listName).equals(link.getCurrentListId());
    }

    private boolean enqueue(PrCardLink link, String listName) {
        PullRequestRef ref = new PullRequestRef(link.getProjectKey(), link.getRepoSlug(), link.getPrId());
        TrelloCardCommand command = new TrelloCardCommand(
                ref,
                link.getTrelloBoardId(),
                // Ни перемещения, ни создания: карточка уже там, где нужно.
                null,
                null,
                // Заголовок, описание, ключ задачи, метки, участники и чек-лист — null:
                // содержимое карточки не меняется, меняется только её статус.
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                link.getListEnteredAt());

        // Ключ выведен из факта «попала в колонку тогда-то», а не из текущего времени:
        // повторный проход ничего не добавит, а карточка, вернувшаяся в эту колонку
        // заново, получит собственную задачу.
        String dedupKey = "trello:complete:%s:%d".formatted(ref.asKey(), link.getListEnteredAt().getEpochSecond());
        boolean queued = taskService.enqueue(OutboundTarget.TRELLO, "syncCard", dedupKey,
                objectMapper.writeValueAsString(command));
        if (queued) {
            log.info("Card {} for {} has sat in '{}' since {}; queueing it as complete",
                    link.getTrelloCardId(), ref.asKey(), listName, link.getListEnteredAt());
        }
        return queued;
    }
}
