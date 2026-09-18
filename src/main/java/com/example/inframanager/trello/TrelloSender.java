package com.example.inframanager.trello;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
        // Перезаписывается, а не ставится один раз: у карточек, заведённых до появления
        // этого поля, стоит приблизительное значение из миграции, и первое же событие о
        // закрытии должно его исправить.
        if (command.closedAt() != null && !command.closedAt().equals(link.getClosedAt())) {
            link.setClosedAt(command.closedAt());
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
        if (command.archive() || command.title() == null) {
            // Создавать нечего: либо PR удалили раньше, чем мы успели его отзеркалить,
            // либо это правка существующей карточки — перемещение сверкой, отметка о
            // выполнении, — а карточки к моменту отправки уже нет. Завести вместо неё
            // безымянную хуже, чем не делать ничего.
            log.debug("Skipping {} for {} -- no card was ever created",
                    command.archive() ? "archive" : "update", command.pullRequest().asKey());
            return;
        }

        String listName = command.moveToListName() != null ? command.moveToListName() : command.createInListName();
        String listId = listResolver.listId(command.boardId(), listName);

        TrelloClient.TrelloCard card = client.createCard(properties.key(), properties.token(),
                new TrelloClient.CreateCardRequest(listId, command.title(), command.description(), "top",
                        labelIds(command), memberIds(command), isoSeconds(command.listEnteredAt())));

        link.recordCard(card.id(), listId, false);
        link.enteredList(command.listEnteredAt());
        applyCover(card.id(), command);
        checklistSync.sync(card.id(), command.checklist());
        log.info("Created Trello card {} for {} in list '{}'", card.id(), command.pullRequest().asKey(), listName);
    }

    /**
     * Обложка новой карточки ставится вторым запросом: {@code POST /1/cards} параметра
     * {@code cover} не принимает, его понимает только {@code PUT}.
     *
     * <p>Снимать при этом нечего — у только что созданной карточки обложки и так нет, —
     * поэтому «без фона» обходится без лишнего запроса.
     *
     * <p>Упасть этот запрос права не имеет: транзакция унесла бы с собой и связку с
     * только что созданной карточкой, а повтор задачи завёл бы вторую такую же. Фон —
     * косметика, и следующее же событие по этому пул-реквесту его поставит.
     */
    private void applyCover(String cardId, TrelloCardCommand command) {
        if (!(coverFor(command) instanceof TrelloClient.Cover cover)) {
            return;
        }
        try {
            client.updateCard(cardId, properties.key(), properties.token(),
                    TrelloClient.UpdateCardRequest.coverOnly(cover));
        } catch (Exception e) {
            log.warn("Could not set the {} cover on new Trello card {}", cover.color(), cardId, e);
        }
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
     * @return обложка, {@link TrelloClient.Cover#NONE} — снять её, либо null — не трогать
     */
    private Object coverFor(TrelloCardCommand command) {
        if (command.coverColor() == null) {
            return null;
        }
        if (command.coverColor().isBlank()) {
            return TrelloClient.Cover.NONE;
        }
        return new TrelloClient.Cover(command.coverColor(), properties.coverSize());
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

    /**
     * Момент для Trello; null означает «поле не трогать» и так же передаётся дальше.
     * Округляется до секунды: Postgres хранит момент с точностью до микросекунд, а в
     * дате карточки эти знаки — мусор, который Trello ещё и может не принять.
     */
    private static String isoSeconds(Instant moment) {
        return moment == null ? null : DateTimeFormatter.ISO_INSTANT.format(moment.truncatedTo(ChronoUnit.SECONDS));
    }

    private void updateCard(TrelloCardCommand command, PrCardLink link) {
        String listId = command.moveToListName() == null
                ? null
                : listResolver.listId(command.boardId(), command.moveToListName());
        boolean complete = command.completeAsOf() != null;
        boolean entersList = !complete && changesList(command, link, listId);
        if (entersList) {
            link.enteredList(command.listEnteredAt());
            // Карточка снова о незаконченной работе: отметку забываем, и суточный
            // проход получает право поставить её заново.
            link.clearCompleted();
        }

        // Срок принадлежит одной лишь отметке о выполнении, поэтому и выводится из неё,
        // а не из того, что сейчас произошло. Нет отметки — нет и срока: непогашенный,
        // он горел бы на карточке красным, а дата в прошлом у нас на каждой.
        boolean completed = complete || link.getCompletedAt() != null;
        Object due = complete ? isoSeconds(command.completeAsOf())
                : completed ? null : TrelloClient.UpdateCardRequest.NO_DUE;
        Boolean dueComplete = complete ? Boolean.TRUE : completed ? null : Boolean.FALSE;

        // Дата начала отсылается на каждом обновлении, а не только при переезде: она
        // такая же часть желаемого состояния карточки, как заголовок. Стёртая руками
        // вернётся следующим же событием, а перерисовка доски проставит её всем.
        client.updateCard(link.getTrelloCardId(), properties.key(), properties.token(),
                new TrelloClient.UpdateCardRequest(listId, command.title(), command.description(),
                        command.archive(), labelIds(command), memberIds(command),
                        isoSeconds(link.getListEnteredAt()), due, dueComplete,
                        coverFor(command)));

        link.recordCard(link.getTrelloCardId(),
                listId != null ? listId : link.getCurrentListId(),
                command.archive());
        if (complete) {
            link.markCompleted();
        }
        checklistSync.sync(link.getTrelloCardId(), command.checklist());
        log.info("Updated Trello card {} for {}{}{}", link.getTrelloCardId(), command.pullRequest().asKey(),
                command.moveToListName() == null ? "" : " -> list '" + command.moveToListName() + "'",
                complete ? " (complete)"
                        : entersList ? " (start " + isoSeconds(link.getListEnteredAt()) + ")" : "");
    }

    /**
     * Меняет ли эта команда колонку карточки — то есть надо ли переставить дату начала.
     *
     * <p>Спрашивается именно про смену, а не про наличие колонки в команде: события,
     * отображённые на ту же колонку, где карточка уже лежит, идут потоком, и сбрасывать
     * дату на каждом значило бы вместо «в ревью с понедельника» показывать «в ревью
     * с последнего касания».
     *
     * <p>Команда без момента переезда даты не касается вовсе. Так двигает карточку
     * сверка: она возвращает на место перетащенную руками, а это не смена состояния,
     * и датировать им карточку было бы враньём.
     */
    private static boolean changesList(TrelloCardCommand command, PrCardLink link, String listId) {
        return command.listEnteredAt() != null && listId != null && !listId.equals(link.getCurrentListId());
    }
}
