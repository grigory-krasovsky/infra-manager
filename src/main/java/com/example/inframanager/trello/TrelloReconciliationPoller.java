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
 * Сверяет то, что мы думаем о каждой карточке, с тем, что на самом деле в Trello.
 *
 * <p>Синхронизация односторонняя, поэтому больше никто и не заметил бы, что карточку
 * перетащили в другую колонку или удалили. Ради этого в устройстве системы и есть
 * половина «опрашивать Trello».
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

    /** @return сколько расхождений нашёл этот проход */
    @Transactional
    public int runOnce() {
        int discrepancies = 0;
        for (PrCardLink link : linkRepository.findByArchivedFalseAndTrelloCardIdIsNotNull()) {
            try {
                discrepancies += check(link);
            } catch (Exception e) {
                // Одна проблемная карточка не должна прерывать весь обход.
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
            // Принимаем версию Trello, чтобы не сообщать об одном и том же расхождении каждый проход.
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
                // Заголовок, описание, метки, участники и чек-лист остаются null, чтобы
                // содержимое карточки не менялось; UpdateCardRequest пропускает null'ы,
                // так что это чистое перемещение.
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null);

        // Разложено по минутным корзинам: несколько проходов, видящих одно и то же
        // неисправленное расхождение, схлопываются в одну задачу, а по-настоящему второе
        // расхождение позже всё равно получит свою.
        String bucket = String.valueOf(Instant.now().truncatedTo(ChronoUnit.MINUTES).getEpochSecond());
        taskService.enqueue(OutboundTarget.TRELLO, "syncCard",
                "trello:reconcile:%s:%s".formatted(pr, bucket),
                objectMapper.writeValueAsString(command));
        return 1;
    }
}
