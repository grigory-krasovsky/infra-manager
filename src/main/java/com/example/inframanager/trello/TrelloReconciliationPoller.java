package com.example.inframanager.trello;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.example.inframanager.outbound.OutboundTarget;
import com.example.inframanager.outbound.OutboundTaskService;
import com.example.inframanager.pullrequest.PrCardLink;
import com.example.inframanager.pullrequest.PrCardLinkRepository;
import com.example.inframanager.pullrequest.PullRequestRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.ObjectMapper;

/**
 * Compares what we believe about each card against what Trello actually has.
 *
 * <p>The sync is one-way, so nothing else would ever notice a card being dragged to
 * another column or deleted. This is what the "poll Trello" half of the design is
 * for.
 */
public class TrelloReconciliationPoller {

    private static final Logger log = LoggerFactory.getLogger(TrelloReconciliationPoller.class);

    private final TrelloClient client;
    private final TrelloListResolver listResolver;
    private final TrelloProperties properties;
    private final PrCardLinkRepository linkRepository;
    private final OutboundTaskService taskService;
    private final ObjectMapper objectMapper;

    public TrelloReconciliationPoller(TrelloClient client,
                                      TrelloListResolver listResolver,
                                      TrelloProperties properties,
                                      PrCardLinkRepository linkRepository,
                                      OutboundTaskService taskService,
                                      ObjectMapper objectMapper) {
        this.client = client;
        this.listResolver = listResolver;
        this.properties = properties;
        this.linkRepository = linkRepository;
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    /** @return how many discrepancies this pass found */
    @Transactional
    public int runOnce() {
        int discrepancies = 0;
        for (PrCardLink link : linkRepository.findByArchivedFalseAndTrelloCardIdIsNotNull()) {
            try {
                discrepancies += check(link);
            } catch (Exception e) {
                // One bad card must not abort the sweep.
                log.warn("Could not reconcile card {} for {}/{}/{}", link.getTrelloCardId(),
                        link.getProjectKey(), link.getRepoSlug(), link.getPrId(), e);
            }
        }
        return discrepancies;
    }

    private int check(PrCardLink link) {
        TrelloClient.TrelloCard card;
        try {
            card = client.card(link.getTrelloCardId(), properties.key(), properties.token());
        } catch (HttpClientErrorException.NotFound e) {
            return handleMissing(link);
        }

        if (card == null || card.idList() == null || card.idList().equals(link.getCurrentListId())) {
            return 0;
        }
        return handleDrift(link, card);
    }

    private int handleMissing(PrCardLink link) {
        String pr = "%s/%s/%d".formatted(link.getProjectKey(), link.getRepoSlug(), link.getPrId());
        if (properties.reconciliation().onMissing() == TrelloProperties.Reconciliation.OnMissing.FORGET) {
            log.warn("Card {} for {} is gone from Trello; forgetting it so the next event recreates one",
                    link.getTrelloCardId(), pr);
            link.recordCard(null, null, false);
        } else {
            log.warn("Card {} for {} is gone from Trello", link.getTrelloCardId(), pr);
        }
        return 1;
    }

    private int handleDrift(PrCardLink link, TrelloClient.TrelloCard card) {
        String expected = listResolver.listName(link.getTrelloBoardId(), link.getCurrentListId())
                .orElse(link.getCurrentListId());
        String actual = listResolver.listName(link.getTrelloBoardId(), card.idList())
                .orElse(card.idList());
        String pr = "%s/%s/%d".formatted(link.getProjectKey(), link.getRepoSlug(), link.getPrId());

        if (properties.reconciliation().onDrift() != TrelloProperties.Reconciliation.OnDrift.RESTORE) {
            log.warn("Card {} for {} sits in '{}' but we expected '{}'",
                    card.id(), pr, actual, expected);
            // Adopt what Trello says, so the same drift is not reported every pass.
            link.recordCard(card.id(), card.idList(), card.closed());
            return 1;
        }

        log.warn("Card {} for {} drifted from '{}' to '{}'; queueing a move back",
                card.id(), pr, expected, actual);
        TrelloCardCommand command = new TrelloCardCommand(
                new PullRequestRef(link.getProjectKey(), link.getRepoSlug(), link.getPrId()),
                link.getTrelloBoardId(),
                expected,
                expected,
                // Title, description, labels and members stay null so the card's
                // content is untouched; UpdateCardRequest omits nulls, making this a
                // pure move.
                null,
                null,
                null,
                null,
                null,
                false);

        // Bucketed by minute: repeated passes seeing the same unfixed drift collapse
        // into one task, but a genuine second drift later still gets its own.
        String bucket = String.valueOf(Instant.now().truncatedTo(ChronoUnit.MINUTES).getEpochSecond());
        taskService.enqueue(OutboundTarget.TRELLO, "syncCard",
                "trello:reconcile:%s:%s".formatted(pr, bucket),
                objectMapper.writeValueAsString(command));
        return 1;
    }
}
