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
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Разово отсылает каждой карточке то, что мы о ней знаем, — чтобы после раскатки,
 * поменявшей вид карточки, увидеть новое не дожидаясь событий из Bitbucket.
 *
 * <p>Команда уходит пустая: ни колонки, ни содержимого. Это не бедность, а весь смысл —
 * карточка приводится к состоянию, которое хранится у нас, и ничего сверх него не
 * трогается. Отправитель на такой команде отсылает дату начала из связки и снимает срок
 * у карточки без отметки о выполнении.
 *
 * <p>Заголовок, описание, метки и чек-лист так не обновить: они выводятся из
 * пул-реквеста, а его надо спрашивать у Bitbucket. Проход намеренно туда не ходит —
 * перерисовать доску он должен дёшево и не завися от того, отвечает ли Bitbucket.
 *
 * <p>В отличие от опроса, разбирает и карточки закрытых пул-реквестов: событий по ним
 * больше не будет никогда, а значит, без этого прохода они не изменились бы уже ничем.
 */
public class TrelloCardRepaint implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TrelloCardRepaint.class);

    private final PrCardLinkRepository linkRepository;
    private final OutboundTaskService taskService;
    private final ObjectMapper objectMapper;

    public TrelloCardRepaint(PrCardLinkRepository linkRepository,
                             OutboundTaskService taskService,
                             ObjectMapper objectMapper) {
        this.linkRepository = linkRepository;
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // Одна корзина на весь проход: повтор запуска в ту же минуту — это перезапуск
        // сервиса, и второй раз перерисовывать доску незачем. Следующая раскатка
        // попадёт в другую минуту и получит свои задачи.
        String bucket = String.valueOf(Instant.now().truncatedTo(ChronoUnit.MINUTES).getEpochSecond());

        int queued = 0;
        for (PrCardLink link : linkRepository.findByArchivedFalseAndTrelloCardIdIsNotNull()) {
            if (enqueue(link, bucket)) {
                queued++;
            }
        }
        log.info("Repaint on start: queued {} card(s) to be redrawn from what we store", queued);
    }

    private boolean enqueue(PrCardLink link, String bucket) {
        PullRequestRef ref = new PullRequestRef(link.getProjectKey(), link.getRepoSlug(), link.getPrId());
        TrelloCardCommand command = new TrelloCardCommand(
                ref,
                link.getTrelloBoardId(),
                // Колонка, место создания, заголовок, описание, ключ задачи, метки,
                // обложка, участники, чек-лист, момент переезда и момент закрытия — всё
                // null: карточке нечего сообщить кроме того, что у нас и так записано.
                null, null, null, null, null, null, null, null, null,
                false,
                null,
                null,
                null);

        return taskService.enqueue(OutboundTarget.TRELLO, "syncCard",
                "trello:repaint:%s:%s".formatted(ref.asKey(), bucket),
                objectMapper.writeValueAsString(command));
    }
}
