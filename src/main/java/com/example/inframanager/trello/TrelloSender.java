package com.example.inframanager.trello;

import java.time.Duration;

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
 * Performs TRELLO outbound tasks: creates the card if it does not exist yet, then
 * brings it to the state the command asks for.
 *
 * <p>Runs inside the outbound worker's transaction, so the card id Trello returns
 * and the {@code pr_card_link} row that records it are committed together. Losing
 * that association would mean creating a duplicate card on the next event.
 */
public class TrelloSender implements OutboundTaskSender {

    private static final Logger log = LoggerFactory.getLogger(TrelloSender.class);

    /** Trello does not send Retry-After; its window is measured in seconds. */
    private static final Duration RATE_LIMIT_BACKOFF = Duration.ofSeconds(10);

    private final TrelloClient client;
    private final TrelloListResolver listResolver;
    private final TrelloProperties properties;
    private final PrCardLinkRepository linkRepository;
    private final ObjectMapper objectMapper;

    public TrelloSender(TrelloClient client,
                        TrelloListResolver listResolver,
                        TrelloProperties properties,
                        PrCardLinkRepository linkRepository,
                        ObjectMapper objectMapper) {
        this.client = client;
        this.listResolver = listResolver;
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
            // Nothing to archive: the PR was deleted before we ever mirrored it.
            log.debug("Skipping archive for {} -- no card was ever created", command.pullRequest().asKey());
            return;
        }

        String listName = command.moveToListName() != null ? command.moveToListName() : command.createInListName();
        String listId = listResolver.listId(command.boardId(), listName);

        TrelloClient.TrelloCard card = client.createCard(properties.key(), properties.token(),
                new TrelloClient.CreateCardRequest(listId, command.title(), command.description(), "top"));

        link.recordCard(card.id(), listId, false);
        log.info("Created Trello card {} for {} in list '{}'", card.id(), command.pullRequest().asKey(), listName);
    }

    private void updateCard(TrelloCardCommand command, PrCardLink link) {
        String listId = command.moveToListName() == null
                ? null
                : listResolver.listId(command.boardId(), command.moveToListName());

        client.updateCard(link.getTrelloCardId(), properties.key(), properties.token(),
                new TrelloClient.UpdateCardRequest(listId, command.title(), command.description(), command.archive()));

        link.recordCard(link.getTrelloCardId(),
                listId != null ? listId : link.getCurrentListId(),
                command.archive());
        log.info("Updated Trello card {} for {}{}", link.getTrelloCardId(), command.pullRequest().asKey(),
                command.moveToListName() == null ? "" : " -> list '" + command.moveToListName() + "'");
    }
}
