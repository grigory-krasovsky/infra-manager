package com.example.inframanager.trello;

import java.time.Duration;
import java.util.List;

import com.example.inframanager.outbound.OutboundTarget;
import com.example.inframanager.outbound.OutboundTask;
import com.example.inframanager.outbound.OutboundTaskSender;
import com.example.inframanager.pullrequest.PrCardLink;
import com.example.inframanager.pullrequest.PrCardLinkRepository;
import com.example.inframanager.work.RetryAfterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.ObjectMapper;

/**
 * Выполняет исходящие задачи TRELLO: создаёт карточку, если её ещё нет, и приводит её
 * в состояние, которого требует команда.
 *
 * <p>Работает внутри транзакции исходящего воркера, поэтому id карточки, который вернул
 * Trello, и строка {@code pr_card_link} с этой записью коммитятся вместе. Потеря этой
 * связи означала бы создание карточки-дубликата на следующем событии.
 */
public class TrelloSender implements OutboundTaskSender {

    private static final Logger log = LoggerFactory.getLogger(TrelloSender.class);

    /** Trello не присылает Retry-After; его окно измеряется секундами. */
    private static final Duration RATE_LIMIT_BACKOFF = Duration.ofSeconds(10);

    private final TrelloClient client;
    private final TrelloListResolver listResolver;
    private final TrelloLabelResolver labelResolver;
    private final TrelloMemberResolver memberResolver;
    private final TrelloChecklistSync checklistSync;
    private final TrelloProperties properties;
    private final PrCardLinkRepository linkRepository;
    private final ObjectMapper objectMapper;

    public TrelloSender(TrelloClient client,
                        TrelloListResolver listResolver,
                        TrelloLabelResolver labelResolver,
                        TrelloMemberResolver memberResolver,
                        TrelloChecklistSync checklistSync,
                        TrelloProperties properties,
                        PrCardLinkRepository linkRepository,
                        ObjectMapper objectMapper) {
        this.client = client;
        this.listResolver = listResolver;
        this.labelResolver = labelResolver;
        this.memberResolver = memberResolver;
        this.checklistSync = checklistSync;
        this.properties = properties;
        this.linkRepository = linkRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public OutboundTarget target() {
        return OutboundTarget.TRELLO;
    }

    @Override
    public void send(OutboundTask task) {
        TrelloCardCommand command = objectMapper.readValue(task.getPayload(), TrelloCardCommand.class);
        PrCardLink link = linkRepository.find(command.pullRequest())
                .orElseGet(() -> linkRepository.save(new PrCardLink(command.pullRequest(), command.boardId())));

        if (command.issueKey() != null && !command.issueKey().equals(link.getIssueKey())) {
            link.setIssueKey(command.issueKey());
        }

        try {
            if (link.getTrelloCardId() == null) {
                createCard(command, link);
            } else {
                updateCard(command, link);
            }
        } catch (HttpClientErrorException.TooManyRequests e) {
            throw new RetryAfterException("Trello rate limited " + command.pullRequest().asKey(), RATE_LIMIT_BACKOFF);
        }
    }

    private void createCard(TrelloCardCommand command, PrCardLink link) {
        if (command.archive()) {
            // Архивировать нечего: PR удалили раньше, чем мы успели его отзеркалить.
            log.debug("Skipping archive for {} -- no card was ever created", command.pullRequest().asKey());
            return;
        }

        String listName = command.moveToListName() != null ? command.moveToListName() : command.createInListName();
        String listId = listResolver.listId(command.boardId(), listName);

        TrelloClient.TrelloCard card = client.createCard(properties.key(), properties.token(),
                new TrelloClient.CreateCardRequest(listId, command.title(), command.description(), "top",
                        labelIds(command), memberIds(command)));

        link.recordCard(card.id(), listId, false);
        checklistSync.sync(card.id(), command.checklist());
        log.info("Created Trello card {} for {} in list '{}'", card.id(), command.pullRequest().asKey(), listName);
    }

    /**
     * @return id меток через запятую либо null — не трогать метки карточки
     */
    private String labelIds(TrelloCardCommand command) {
        if (command.labels() == null || command.labels().isEmpty()) {
            return null;
        }
        List<String> ids = labelResolver.labelIds(command.boardId(), command.labels());
        return ids.isEmpty() ? null : String.join(",", ids);
    }

    /**
     * @return id участников через запятую либо null — не трогать участников карточки.
     *         Автор без учётной записи в Trello не разрешается ни во что, и это нормально.
     */
    private String memberIds(TrelloCardCommand command) {
        if (command.memberCandidates() == null || command.memberCandidates().isEmpty()) {
            return null;
        }
        List<String> ids = memberResolver.memberIds(command.boardId(), command.memberCandidates());
        return ids.isEmpty() ? null : String.join(",", ids);
    }

    private void updateCard(TrelloCardCommand command, PrCardLink link) {
        String listId = command.moveToListName() == null
                ? null
                : listResolver.listId(command.boardId(), command.moveToListName());

        client.updateCard(link.getTrelloCardId(), properties.key(), properties.token(),
                new TrelloClient.UpdateCardRequest(listId, command.title(), command.description(),
                        command.archive(), labelIds(command), memberIds(command)));

        link.recordCard(link.getTrelloCardId(),
                listId != null ? listId : link.getCurrentListId(),
                command.archive());
        checklistSync.sync(link.getTrelloCardId(), command.checklist());
        log.info("Updated Trello card {} for {}{}", link.getTrelloCardId(), command.pullRequest().asKey(),
                command.moveToListName() == null ? "" : " -> list '" + command.moveToListName() + "'");
    }
}
