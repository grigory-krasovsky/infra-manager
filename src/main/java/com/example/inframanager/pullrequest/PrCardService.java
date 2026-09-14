package com.example.inframanager.pullrequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.example.inframanager.event.EventSource;
import com.example.inframanager.event.InboundEvent;
import com.example.inframanager.event.InboundEventHandler;
import com.example.inframanager.jira.IssueKeyExtractor;
import com.example.inframanager.jira.JiraEnricher;
import com.example.inframanager.outbound.OutboundTarget;
import com.example.inframanager.outbound.OutboundTaskService;
import com.example.inframanager.trello.TrelloCardCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Превращает сохранённое событие пул-реквеста Bitbucket в поставленное в очередь
 * обновление карточки Trello.
 *
 * <p>Решает, <em>как должна выглядеть карточка</em>, и отдаёт это в очередь; сам в Trello
 * никогда не ходит. Так входящий воркер остаётся быстрым, а все вызовы Trello проходят
 * через один ограничитель скорости.
 */
@Component
public class PrCardService implements InboundEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PrCardService.class);
    private static final String DELETED_EVENT = "pr:deleted";

    /** События, после которых пул-реквест уже ничем не станет. */
    private static final List<String> CLOSING_EVENTS = List.of("pr:merged", "pr:declined");

    /** Пункт чек-листа — одна строка; всё, что длиннее, читают в Bitbucket. */
    private static final int CHECKLIST_ITEM_LIMIT = 200;

    private final LifecycleProperties lifecycle;
    private final PrCardContentRenderer renderer;
    private final OutboundTaskService taskService;
    private final IssueKeyExtractor issueKeyExtractor;
    private final JiraEnricher jiraEnricher;
    private final PrTaskReader taskReader;
    private final ObjectMapper objectMapper;

    public PrCardService(LifecycleProperties lifecycle,
                         PrCardContentRenderer renderer,
                         OutboundTaskService taskService,
                         IssueKeyExtractor issueKeyExtractor,
                         JiraEnricher jiraEnricher,
                         PrTaskReader taskReader,
                         ObjectMapper objectMapper) {
        this.lifecycle = lifecycle;
        this.renderer = renderer;
        this.taskService = taskService;
        this.issueKeyExtractor = issueKeyExtractor;
        this.jiraEnricher = jiraEnricher;
        this.taskReader = taskReader;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(EventSource source) {
        return source == EventSource.BITBUCKET;
    }

    @Override
    public void handle(InboundEvent event) {
        BitbucketPrEvent parsed = objectMapper.readValue(event.getPayload(), BitbucketPrEvent.class);

        Optional<PullRequestRef> maybeRef = parsed.ref();
        if (maybeRef.isEmpty()) {
            // Повторять бессмысленно: сколько ни жди, репозиторий в payload'е не появится.
            throw new IllegalArgumentException(
                    "Bitbucket payload has no target repository; cannot identify the pull request");
        }
        PullRequestRef ref = maybeRef.get();

        Optional<LifecycleProperties.RepoBoard> board = lifecycle.boardFor(ref);
        if (board.isEmpty()) {
            // Ожидаемо, когда вебхук включён на большем числе репозиториев, чем мы зеркалим.
            log.debug("No Trello board configured for {}; ignoring {}", ref.asKey(), event.getEventType());
            return;
        }

        String eventKey = event.getEventType();
        boolean archive = DELETED_EVENT.equals(eventKey);

        // «Не знаем» и «конфликтов нет» для заголовка одно и то же: предупреждать можно
        // только о выясненном. Выясняет это путь с опросом — он же кладёт ответ в payload.
        boolean conflicted = parsed.conflicted().orElse(false);

        String issueKey = issueKeyExtractor
                .extract(parsed.sourceBranch(),
                        parsed.pullRequest() == null ? null : parsed.pullRequest().title())
                .orElse(null);
        // Никогда не бросает исключение: карточка с более скупым заголовком лучше, чем её отсутствие.
        String issueSummary = jiraEnricher.summaryFor(issueKey).orElse(null);

        TrelloCardCommand command = new TrelloCardCommand(
                ref,
                board.get().trelloBoardId(),
                lifecycle.listFor(eventKey).orElse(null),
                lifecycle.createInList(),
                renderer.title(parsed, board.get(), issueKey, issueSummary, conflicted),
                renderer.description(parsed, ref),
                issueKey,
                labelsFor(parsed, board.get()),
                coverFor(board.get()),
                authorCandidates(parsed),
                checklistFor(ref),
                archive,
                closedAt(eventKey, parsed),
                // Отметку о выполнении ставит суточный проход по возрасту пул-реквеста,
                // а не событие: событие «неделя прошла» никто не присылает.
                null);

        // Ключуется по входящему событию, поэтому его повтор не поставит в очередь
        // второе такое же обновление карточки.
        String dedupKey = "trello:%s:%s".formatted(ref.asKey(), event.getExternalId());
        taskService.enqueue(OutboundTarget.TRELLO, "syncCard", dedupKey,
                objectMapper.writeValueAsString(command));

        log.info("Queued card sync for {} after {}{}", ref.asKey(), eventKey,
                command.moveToListName() == null ? " (content only)" : " -> '" + command.moveToListName() + "'");
    }

    /**
     * Когда пул-реквест влили или отклонили — с этого момента отсчитывается неделя до
     * отметки «выполнено».
     *
     * <p>Спрашивается и у состояния, и у ключа события: опрос знает состояние, вебхук —
     * событие, и совпадают они не всегда. Если закрытие налицо, а даты в payload'е нет,
     * берётся текущий момент: соврать на минуты лучше, чем не отметить карточку никогда.
     *
     * @return null, если событие не о закрытом пул-реквесте, — тогда связка сохраняет то,
     *         что в ней уже записано
     */
    private Instant closedAt(String eventKey, BitbucketPrEvent parsed) {
        if (!parsed.isClosed() && !CLOSING_EVENTS.contains(eventKey)) {
            return null;
        }
        return parsed.closedInstant().orElseGet(Instant::now);
    }

    /**
     * Проект и целевая ветка. Проект дублирует префикс в заголовке намеренно: по
     * заголовку доску не отфильтруешь, а все репозитории зеркалятся на одну доску, и без
     * метки «показать только своё» на ней не сделать.
     *
     * <p>Автор остаётся участником карточки, а не меткой: аватар считывается быстрее
     * цветной плашки, да и десяти цветов палитры Trello перестаёт хватать задолго до
     * того, как кончится команда.
     */
    private List<String> labelsFor(BitbucketPrEvent event, LifecycleProperties.RepoBoard repo) {
        List<String> labels = new ArrayList<>();
        labels.add(repo.displayPrefix());
        if (StringUtils.hasText(event.targetBranch())) {
            labels.add(lifecycle.labelForBranch(event.targetBranch()));
        }
        return labels;
    }

    /**
     * Фон карточки — по репозиторию. Репозиторию без настроенного цвета достаётся не
     * «оставить как есть», а именно «снять»: убрали цвет из конфигурации — он должен уйти
     * и с доски, иначе однажды покрашенные карточки останутся такими навсегда.
     */
    private static String coverFor(LifecycleProperties.RepoBoard repo) {
        return repo.cover() == null ? TrelloCardCommand.NO_COVER : repo.cover();
    }

    /**
     * Задачи ревью как чек-лист: видно, что просили поправить и что уже закрыто.
     * Колонку они не двигают — её определяет вердикт ревьюера о нынешнем коде.
     *
     * @return пункты чек-листа либо null, если задачи спросить не удалось — тогда
     *         чек-лист на карточке останется таким, каким был
     */
    private List<TrelloCardCommand.ChecklistItem> checklistFor(PullRequestRef ref) {
        return taskReader.tasks(ref)
                .map(tasks -> tasks.stream()
                        .map(task -> new TrelloCardCommand.ChecklistItem(itemName(task), task.resolved()))
                        .toList())
                .orElse(null);
    }

    /**
     * Текст задачи бывает в несколько абзацев, а пункт чек-листа — это одна строка.
     * Берём начало: смысл замечания обычно в первой фразе, а подробности всё равно
     * читают в Bitbucket.
     */
    private static String itemName(PrTask task) {
        String text = task.text() == null ? "" : task.text().replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return "Задача #" + task.id();
        }
        return text.length() <= CHECKLIST_ITEM_LIMIT
                ? text
                : text.substring(0, CHECKLIST_ITEM_LIMIT - 1).trim() + "…";
    }

    /** Сначала логин, потом отображаемое имя: логин из двух менее двусмысленный. */
    private List<String> authorCandidates(BitbucketPrEvent event) {
        List<String> candidates = new ArrayList<>();
        if (StringUtils.hasText(event.authorLogin())) {
            candidates.add(event.authorLogin());
        }
        if (StringUtils.hasText(event.authorName())) {
            candidates.add(event.authorName());
        }
        return candidates;
    }
}
