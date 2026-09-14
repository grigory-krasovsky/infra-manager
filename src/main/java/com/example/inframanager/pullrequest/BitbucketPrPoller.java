package com.example.inframanager.pullrequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.inframanager.event.EventSource;
import com.example.inframanager.event.InboundEventIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.ObjectMapper;

/**
 * Восстанавливает события пул-реквестов опросом — для установок, где Bitbucket не может
 * достучаться до нас.
 *
 * <p>Вебхук сообщает, что произошло. Поллинг же показывает только настоящее, поэтому
 * событие реконструируется сравнением каждого пул-реквеста с {@link PrPollState}.
 * Результат записывается ровно в той форме, которую имеет тело вебхука, — благодаря
 * этому всё, что дальше по цепочке ({@link PrCardService}, жизненный цикл карточки,
 * идемпотентность), общее для обоих путей приёма, а не продублировано.
 *
 * <p>Опрашиваются только репозитории из {@code infra-manager.lifecycle.repos}: события
 * репозитория, который мы не зеркалим, некому обрабатывать.
 */
public class BitbucketPrPoller {

    private static final Logger log = LoggerFactory.getLogger(BitbucketPrPoller.class);

    /**
     * Единственное событие, которое сравнением пул-реквеста с его снимком не получить:
     * сравнивать больше не с чем. Восстанавливается сверкой снимков со списком.
     */
    private static final String DELETED_EVENT = "pr:deleted";

    private final BitbucketClient client;
    private final BitbucketProperties properties;
    private final LifecycleProperties lifecycle;
    private final PrPollStateRepository stateRepository;
    private final PrTaskReader taskReader;
    private final InboundEventIngestService ingestService;
    private final ObjectMapper objectMapper;

    public BitbucketPrPoller(BitbucketClient client,
                             BitbucketProperties properties,
                             LifecycleProperties lifecycle,
                             PrPollStateRepository stateRepository,
                             PrTaskReader taskReader,
                             InboundEventIngestService ingestService,
                             ObjectMapper objectMapper) {
        this.client = client;
        this.properties = properties;
        this.lifecycle = lifecycle;
        this.stateRepository = stateRepository;
        this.taskReader = taskReader;
        this.ingestService = ingestService;
        this.objectMapper = objectMapper;
    }

    /** @return сколько событий восстановил этот проход */
    @Transactional
    public int runOnce() {
        int emitted = 0;
        for (LifecycleProperties.RepoBoard repo : lifecycle.repos()) {
            try {
                emitted += pollRepository(repo);
            } catch (DataAccessException e) {
                // Сбой базы уже пометил транзакцию как rollback-only; проглотив его,
                // мы получили бы позже необъяснимый UnexpectedRollback.
                throw e;
            } catch (Exception e) {
                // Один недоступный репозиторий не должен останавливать остальные.
                log.warn("Failed to poll {}/{}", repo.projectKey(), repo.repoSlug(), e);
            }
        }
        return emitted;
    }

    private int pollRepository(LifecycleProperties.RepoBoard repo) {
        BitbucketClient.PullRequestPage page = client.pullRequests(
                repo.projectKey(), repo.repoSlug(), "ALL", "NEWEST", properties.poll().maxResults());

        if (page == null || page.values() == null) {
            // Ответ без списка — это не «пул-реквестов нет», а «нам не ответили». Судить
            // по нему о том, кто пропал, нельзя, поэтому проход по репозиторию на этом
            // и кончается.
            log.warn("Bitbucket returned no pull request list for {}/{}", repo.projectKey(), repo.repoSlug());
            return 0;
        }
        List<BitbucketPrEvent.PullRequest> pullRequests = page.values();

        int emitted = 0;
        for (BitbucketPrEvent.PullRequest pullRequest : pullRequests) {
            emitted += observe(repo, pullRequest);
        }
        return emitted + sweepDeleted(repo, pullRequests);
    }

    /**
     * Ищет пул-реквесты, которые из Bitbucket исчезли: их удалили.
     *
     * <p>Всё остальное опрос узнаёт, сравнивая пул-реквест с его снимком, — но у
     * удалённого сравнивать уже не с чем, и без этой сверки {@code pr:deleted} на пути с
     * опросом не поднимался бы никогда. Карточка удалённого пул-реквеста так и оставалась
     * бы на доске живой, а заведённый заново из той же ветки пул-реквест получал бы новый
     * номер и, значит, вторую карточку — ровно так доска и двоится.
     *
     * <p>Отсутствие на странице само по себе ничего не доказывает: страница ограничена
     * {@code max-results}, и старый пул-реквест уходит с неё просто от возраста. Поэтому
     * о каждом подозреваемом спрашивается отдельно, и удалённым он считается только по
     * ответу «такого нет». Ошибиться тут дороже, чем не заметить: архивация — это
     * карточка, пропавшая с доски у всех сразу.
     *
     * @return сколько удалений восстановила эта сверка
     */
    private int sweepDeleted(LifecycleProperties.RepoBoard repo,
                             List<BitbucketPrEvent.PullRequest> page) {
        Set<Long> present = page.stream()
                .filter(Objects::nonNull)
                .map(BitbucketPrEvent.PullRequest::id)
                .collect(Collectors.toSet());

        int emitted = 0;
        for (PrPollState state : stateRepository.findOpen(repo.projectKey(), repo.repoSlug())) {
            if (present.contains(state.getPrId())) {
                continue;
            }
            PullRequestRef ref = new PullRequestRef(repo.projectKey(), repo.repoSlug(), state.getPrId());
            if (!gone(ref)) {
                continue;
            }
            // Помечается независимо от того, новое это событие или уже записанное:
            // пометка здесь — это то, что не даст спрашивать Bitbucket о нём каждые
            // две минуты до конца времён.
            state.markDeleted();
            emitted += reportDeleted(ref);
        }
        return emitted;
    }

    /**
     * @return true, только если Bitbucket прямо ответил, что такого пул-реквеста нет.
     *         Молчание и любая другая ошибка — это «не знаем», и карточка остаётся на
     *         доске: следующий проход спросит снова.
     */
    private boolean gone(PullRequestRef ref) {
        try {
            client.pullRequest(ref.projectKey(), ref.repoSlug(), ref.prId());
            return false;
        } catch (HttpClientErrorException.NotFound e) {
            return true;
        } catch (Exception e) {
            log.warn("Could not check whether {} still exists", ref.asKey(), e);
            return false;
        }
    }

    /** @return 1, если удаление записано впервые, иначе 0 */
    private int reportDeleted(PullRequestRef ref) {
        String payload = objectMapper.writeValueAsString(
                new BitbucketPrEvent(DELETED_EVENT, null, deletedPullRequest(ref)));
        // Без отпечатка состояния, в отличие от остальных событий: удалить пул-реквест
        // можно один раз, и повтор прохода после сбоя должен попасть в тот же самый
        // идентификатор, а не завести второе такое же событие.
        String externalId = "poll:" + sha256(ref.asKey() + "|" + DELETED_EVENT);
        boolean ingested = ingestService.ingest(EventSource.BITBUCKET, externalId, DELETED_EVENT, payload);
        if (ingested) {
            log.info("{} is gone from Bitbucket; reconstructed {}", ref.asKey(), DELETED_EVENT);
        }
        return ingested ? 1 : 0;
    }

    /**
     * Payload об удалении — из снимка, а не из ответа Bitbucket: у удалённого
     * пул-реквеста спрашивать уже нечего.
     *
     * <p>Здесь ровно то, чем карточка опознаётся: репозиторий и номер. Содержимого нет и
     * быть не может, но удаление его и не трогает — карточка уезжает в архив в последнем
     * известном виде.
     */
    private BitbucketPrEvent.PullRequest deletedPullRequest(PullRequestRef ref) {
        BitbucketPrEvent.Repository repository = new BitbucketPrEvent.Repository(
                ref.repoSlug(), ref.repoSlug(), new BitbucketPrEvent.Project(ref.projectKey(), ref.projectKey()));
        return new BitbucketPrEvent.PullRequest(
                ref.prId(), null, null, PrPollState.DELETED, null, null, null,
                null, new BitbucketPrEvent.Ref(null, null, null, repository),
                null, null, null, null);
    }

    private int observe(LifecycleProperties.RepoBoard repo, BitbucketPrEvent.PullRequest raw) {
        PullRequestRef ref = new PullRequestRef(repo.projectKey(), repo.repoSlug(), raw.id());
        PrPollState previous = stateRepository.find(ref).orElse(null);

        Boolean conflicted = conflicted(ref, raw, previous);
        BitbucketPrEvent.PullRequest pullRequest = normalize(raw, repo, conflicted);
        BitbucketPrEvent snapshot = new BitbucketPrEvent(null, null, pullRequest);

        // Выжимка по ревьюерам хранится хэшем: в неё входят коммиты, и в отведённые
        // колонке 64 символа она перестала помещаться, а обрезанная молча теряла бы
        // изменения в хвосте — то есть у последних по алфавиту ревьюеров.
        String reviewerDigest = sha256(snapshot.reviewerDigest());
        String taskDigest = taskDigest(ref, snapshot, previous);
        String eventKey = deriveEvent(previous, snapshot, reviewerDigest, taskDigest);

        // Заполняем до сохранения: id генерируется через IDENTITY, поэтому save() вставляет
        // строку сразу, а ещё не наблюдённая строка нарушила бы NOT NULL на state.
        PrPollState state = previous != null ? previous : new PrPollState(ref);
        state.observe(pullRequest.state(), pullRequest.version(),
                snapshot.latestCommit(), reviewerDigest, taskDigest, conflicted);
        if (previous == null) {
            stateRepository.save(state);
        }

        if (eventKey == null) {
            return 0;
        }

        String payload = objectMapper.writeValueAsString(new BitbucketPrEvent(eventKey, null, pullRequest));
        String externalId = externalId(ref, eventKey, snapshot, pullRequest, reviewerDigest, taskDigest,
                conflicted);
        boolean ingested = ingestService.ingest(EventSource.BITBUCKET, externalId, eventKey, payload);
        if (ingested) {
            log.info("Reconstructed {} for {} from polling", eventKey, ref.asKey());
        }
        return ingested ? 1 : 0;
    }

    /**
     * Выясняет, что изменилось с прошлого прохода.
     *
     * <p>Пул-реквест, увиденный впервые, порождает событие, только если он всё ещё
     * открыт. Иначе первый же опрос активного репозитория объявил бы о каждом мерже
     * в его истории.
     *
     * @return ключ события Bitbucket, который надо поднять, или null, если ничего
     *         заслуживающего реакции не изменилось
     */
    private String deriveEvent(PrPollState previous, BitbucketPrEvent snapshot,
                               String reviewerDigest, String taskDigest) {
        String state = snapshot.state();

        if (previous == null) {
            if (!"OPEN".equalsIgnoreCase(state)) {
                return null;
            }
            // Не слепо pr:opened: у открытого пул-реквеста уже есть статус ревью, и объявить
            // его только что открытым — значит вернуть карточку, лежащую в «нужны правки»,
            // обратно в колонку ревью.
            return currentReviewEvent(snapshot, "pr:opened");
        }

        if (!Objects.equals(previous.getState(), state)) {
            if ("MERGED".equalsIgnoreCase(state)) {
                return "pr:merged";
            }
            if ("DECLINED".equalsIgnoreCase(state)) {
                return "pr:declined";
            }
            if ("OPEN".equalsIgnoreCase(state)) {
                // Переоткрыт после отклонения.
                return "pr:opened";
            }
        }

        // Дальше — только про открытый пул-реквест. У закрытого ход не за кем: карточка
        // лежит в «влито» или «отклонено», и вернуть её оттуда в колонку ревью не должны
        // ни вердикт ревьюера, ни правка заголовка. Проверка выглядит лишней ровно до
        // того дня, когда выжимка по ревьюерам поменяет формат и разом «изменится» у всей
        // истории репозитория, — тогда она одна и удержит доску на месте.
        if (!"OPEN".equalsIgnoreCase(state)) {
            return null;
        }

        // Пуш и вердикт ревьюера разбираются вместе, и спор между ними решает не порядок
        // проверок, а коммит, на котором вердикт выставлен. Пуш обесценивает ревью, но
        // только то, что было до него; ревьюер, посмотревший уже новый код, снова главнее.
        boolean pushed = changed(previous.getLatestCommit(), snapshot.latestCommit());
        boolean reviewChanged = changed(previous.getReviewerDigest(), reviewerDigest);
        if (pushed || reviewChanged) {
            // Вердикта о нынешнем коде нет — значит ход снова за ревьюером, и остаётся
            // назвать причину: либо запушили, либо ревьюер снял свой статус.
            return currentReviewEvent(snapshot, pushed ? "pr:from_ref_updated" : "pr:reviewer:unapproved");
        }

        if (snapshot.pullRequest().version() != null
                && !Objects.equals(previous.getVersion(), snapshot.pullRequest().version())) {
            return "pr:modified";
        }

        // Задачи карточку не двигают — pr:modified не отображён ни на какую колонку, —
        // но обновляют её содержимое, а значит и чек-лист.
        if (changed(previous.getTaskDigest(), taskDigest)) {
            return "pr:modified";
        }

        // Конфликт — единственное здесь, что происходит вообще без участия пул-реквеста:
        // его создаёт и убирает чужой мерж в целевую ветку. Ни версия, ни ветка, ни
        // ревьюеры при этом не меняются, так что заметить это можно только сравнением.
        // Колонку он тоже не двигает: «чей ход» по-прежнему решает ревью.
        if (changed(previous.getConflicted(), snapshot.conflicted().orElse(null))) {
            return "pr:modified";
        }
        return null;
    }

    /**
     * Мешают ли конфликты влить этот пул-реквест.
     *
     * <p>Сначала смотрим в уже полученный ответ: результат пробного мержа Bitbucket кладёт
     * прямо в список пул-реквестов. Считает он его лениво, и пока пул-реквест никто не
     * открывал, там лежит ответ о старом коде, — вот тогда и спрашиваем отдельно, благо
     * тот запрос заодно заставляет мерж пересчитать.
     *
     * <p>Не удалось узнать — остаётся прошлый ответ: выдуманное «конфликтов нет» сняло бы
     * значок с карточки на первой же заминке Bitbucket.
     *
     * @return null, если ответа нет вовсе, — и у закрытого пул-реквеста, которому уже
     *         нечем конфликтовать
     */
    private Boolean conflicted(PullRequestRef ref, BitbucketPrEvent.PullRequest raw, PrPollState previous) {
        if (!"OPEN".equalsIgnoreCase(raw.state())) {
            return null;
        }
        return raw.conflicted()
                .or(() -> mergeStatus(ref))
                .orElse(previous == null ? null : previous.getConflicted());
    }

    /** @return ответ Bitbucket о конфликте либо {@code empty}, если спросить не удалось */
    private Optional<Boolean> mergeStatus(PullRequestRef ref) {
        try {
            BitbucketClient.MergeStatus status =
                    client.mergeStatus(ref.projectKey(), ref.repoSlug(), ref.prId());
            return status == null ? Optional.empty() : Optional.ofNullable(status.conflicted());
        } catch (Exception e) {
            // Один пул-реквест без ответа не должен прерывать обход репозитория.
            log.warn("Could not read merge status of {}", ref.asKey(), e);
            return Optional.empty();
        }
    }

    /**
     * Приводит пул-реквест из списка к тому виду, в котором его увидит обработчик.
     *
     * <p>Endpoint со списком может не прислать репозиторий в ref'е; карточка ключуется по
     * нему, поэтому подставляем его из конфигурации, а не роняем обработку дальше по цепочке.
     *
     * <p>Ответ о конфликте кладётся сюда уже разрешённым и помеченным свежим: дальше по
     * цепочке его читают, а не выясняют заново, и повтор задачи даёт тот же ответ, что и
     * первая попытка.
     */
    private BitbucketPrEvent.PullRequest normalize(BitbucketPrEvent.PullRequest pullRequest,
                                                   LifecycleProperties.RepoBoard repo,
                                                   Boolean conflicted) {
        BitbucketPrEvent.Repository fallback = new BitbucketPrEvent.Repository(
                repo.repoSlug(), repo.repoSlug(), new BitbucketPrEvent.Project(repo.projectKey(), repo.projectKey()));

        BitbucketPrEvent.Ref toRef = pullRequest.toRef();
        BitbucketPrEvent.Ref resolved = toRef == null
                ? new BitbucketPrEvent.Ref(null, null, null, fallback)
                : new BitbucketPrEvent.Ref(toRef.id(), toRef.displayId(), toRef.latestCommit(),
                        toRef.repository() != null ? toRef.repository() : fallback);

        BitbucketPrEvent.Properties properties = conflicted == null
                ? null
                : new BitbucketPrEvent.Properties(new BitbucketPrEvent.MergeResult(
                        conflicted ? BitbucketPrEvent.MergeResult.CONFLICTED
                                : BitbucketPrEvent.MergeResult.CLEAN,
                        true));

        return new BitbucketPrEvent.PullRequest(
                pullRequest.id(), pullRequest.title(), pullRequest.description(), pullRequest.state(),
                pullRequest.version(), pullRequest.updatedDate(), pullRequest.closedDate(),
                pullRequest.fromRef(), resolved,
                pullRequest.author(), pullRequest.reviewers(), pullRequest.links(), properties);
    }

    /**
     * Идентифицирует переход состояния: повторный опрос неизменившегося пул-реквеста даёт
     * дубликат.
     *
     * <p>Конфликт входит сюда наравне с остальным, и не для полноты: появившийся и
     * разрешённый конфликт дают два {@code pr:modified}, у которых совпадает решительно
     * всё остальное. Без него второй из них был бы отброшен как повтор первого, и значок
     * с карточки уже не снялся бы.
     */
    private String externalId(PullRequestRef ref, String eventKey, BitbucketPrEvent snapshot,
                              BitbucketPrEvent.PullRequest pullRequest, String reviewerDigest,
                              String taskDigest, Boolean conflicted) {
        String fingerprint = String.join("|", ref.asKey(), eventKey,
                String.valueOf(pullRequest.state()), String.valueOf(pullRequest.version()),
                String.valueOf(snapshot.latestCommit()), String.valueOf(reviewerDigest),
                String.valueOf(taskDigest), String.valueOf(conflicted));
        return "poll:" + sha256(fingerprint);
    }

    /**
     * Отпечаток задач — только у открытых пул-реквестов: у закрытого чек-лист уже
     * ничего не решает, а запрос стоит вызова на каждый пул-реквест за проход.
     *
     * <p>Не удалось спросить — возвращаем прошлый отпечаток: выдуманное «задач больше
     * нет» стёрло бы чек-лист с карточки на первой же заминке Bitbucket.
     */
    private String taskDigest(PullRequestRef ref, BitbucketPrEvent snapshot, PrPollState previous) {
        String previousDigest = previous == null ? null : previous.getTaskDigest();
        if (!"OPEN".equalsIgnoreCase(snapshot.state())) {
            return previousDigest;
        }
        return taskReader.tasks(ref).map(PrTaskReader::digest).orElse(previousDigest);
    }

    /**
     * Где открытому пул-реквесту место прямо сейчас — судя по вердиктам его ревьюеров о
     * нынешней голове ветки.
     *
     * <p>Вердикт, вынесенный до последнего пуша, не считается вовсе: «нужны правки»,
     * сказанные про уже переписанный код, означают, что ход за ревьюером, а не за автором.
     * Ровно поэтому статуса самого по себе мало — {@code NEEDS_WORK} висит и после того,
     * как автор всё починил, и снимать его в Bitbucket никто не приучен.
     *
     * @param fallback чем назвать положение, когда о нынешнем коде не высказался никто
     */
    private String currentReviewEvent(BitbucketPrEvent snapshot, String fallback) {
        if (snapshot.hasCurrentChangesRequested()) {
            return "pr:reviewer:changes_requested";
        }
        return snapshot.hasCurrentApproval() ? "pr:reviewer:approved" : fallback;
    }

    /** Неизвестное «сейчас» изменением не считается: не знать и увидеть новое — разное. */
    private static boolean changed(Object previous, Object current) {
        return current != null && !Objects.equals(previous, current);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
