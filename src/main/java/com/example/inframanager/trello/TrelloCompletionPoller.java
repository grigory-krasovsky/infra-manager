package com.example.inframanager.trello;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

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
 * Отмечает выполненными карточки пул-реквестов, закрытых неделю назад и больше.
 *
 * <p>Конечных исходов у пул-реквеста два — влит и отклонён, — и для доски они
 * равнозначны: решение принято, и через неделю к нему уже не возвращаются. Отклонённый
 * ничем не «хуже» влитого, потому что отметка говорит не «сделано хорошо», а «здесь
 * всё закончено».
 *
 * <p>Неделя считается от закрытия пул-реквеста в Bitbucket, а не от переезда карточки:
 * в колонку она попадает позже — на время простоя сервиса, а перетащенная руками и на
 * сколько угодно, — и отсчёт от доски растягивал бы срок на ровном месте.
 *
 * <p>Колонка при этом всё равно проверяется: карточка, которую увезли из «влито» или
 * «отклонено», на доске больше не о законченной работе, что бы ни было в Bitbucket.
 * Имена колонок берутся из отображения {@code pr:merged} и {@code pr:declined} — заводить
 * вторую настройку с тем же смыслом значило бы дать им однажды разойтись.
 *
 * <p>Это опрос, а не обработчик события, потому что события тут и нет: «прошла неделя»
 * никто не присылает, это можно только заметить самому.
 */
public class TrelloCompletionPoller {

    private static final Logger log = LoggerFactory.getLogger(TrelloCompletionPoller.class);

    /** События, которыми жизнь пул-реквеста кончается, — и колонки, куда они кладут карточку. */
    private static final List<String> CLOSING_EVENTS = List.of("pr:merged", "pr:declined");

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
        List<String> finalLists = CLOSING_EVENTS.stream()
                .map(event -> lifecycle.listFor(event).orElse(null))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (finalLists.isEmpty()) {
            // Ни одно закрывающее событие не отображено на колонку: доске нечего считать
            // законченным.
            log.debug("No list is mapped to any of {}; nothing can age into 'complete'", CLOSING_EVENTS);
            return 0;
        }

        Instant closedBefore = Instant.now().minus(properties.completion().after());
        int queued = 0;
        for (PrCardLink link : linkRepository.findClosedBefore(closedBefore)) {
            try {
                String listName = sittingIn(link, finalLists);
                if (listName != null && enqueue(link, listName)) {
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

    /** @return имя конечной колонки, в которой лежит карточка, либо null — она не в конечной */
    private String sittingIn(PrCardLink link, List<String> finalLists) {
        for (String listName : finalLists) {
            if (listResolver.listId(link.getTrelloBoardId(), listName).equals(link.getCurrentListId())) {
                return listName;
            }
        }
        return null;
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
                // Момент закрытия уже записан в связку, повторять его команде незачем.
                null,
                link.getClosedAt());

        // Ключ выведен из факта «закрыт тогда-то», а не из текущего времени: повторный
        // проход ничего не добавит, а пул-реквест, закрытый заново после переоткрытия,
        // получит собственную задачу.
        String dedupKey = "trello:complete:%s:%d".formatted(ref.asKey(), link.getClosedAt().getEpochSecond());
        boolean queued = taskService.enqueue(OutboundTarget.TRELLO, "syncCard", dedupKey,
                objectMapper.writeValueAsString(command));
        if (queued) {
            log.info("{} was closed at {} and its card {} sits in '{}'; queueing it as complete",
                    ref.asKey(), link.getClosedAt(), link.getTrelloCardId(), listName);
        }
        return queued;
    }
}
