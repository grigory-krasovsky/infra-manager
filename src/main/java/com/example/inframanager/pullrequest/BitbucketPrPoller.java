package com.example.inframanager.pullrequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import com.example.inframanager.event.EventSource;
import com.example.inframanager.event.InboundEventIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Transactional;
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

        List<BitbucketPrEvent.PullRequest> pullRequests =
                page == null || page.values() == null ? List.of() : page.values();

        int emitted = 0;
        for (BitbucketPrEvent.PullRequest pullRequest : pullRequests) {
            emitted += observe(repo, pullRequest);
        }
        return emitted;
    }

    private int observe(LifecycleProperties.RepoBoard repo, BitbucketPrEvent.PullRequest raw) {
        BitbucketPrEvent.PullRequest pullRequest = withRepository(raw, repo);
        PullRequestRef ref = new PullRequestRef(repo.projectKey(), repo.repoSlug(), pullRequest.id());
        BitbucketPrEvent snapshot = new BitbucketPrEvent(null, null, pullRequest);

        PrPollState previous = stateRepository.find(ref).orElse(null);
        String taskDigest = taskDigest(ref, snapshot, previous);
        String eventKey = deriveEvent(previous, snapshot, taskDigest);

        // Заполняем до сохранения: id генерируется через IDENTITY, поэтому save() вставляет
        // строку сразу, а ещё не наблюдённая строка нарушила бы NOT NULL на state.
        PrPollState state = previous != null ? previous : new PrPollState(ref);
        state.observe(pullRequest.state(), pullRequest.version(),
                snapshot.latestCommit(), snapshot.reviewerDigest(), taskDigest);
        if (previous == null) {
            stateRepository.save(state);
        }

        if (eventKey == null) {
            return 0;
        }

        String payload = objectMapper.writeValueAsString(new BitbucketPrEvent(eventKey, null, pullRequest));
        String externalId = externalId(ref, eventKey, snapshot, pullRequest, taskDigest);
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
    private String deriveEvent(PrPollState previous, BitbucketPrEvent snapshot, String taskDigest) {
        String state = snapshot.state();

        if (previous == null) {
            if (!"OPEN".equalsIgnoreCase(state)) {
                return null;
            }
            // Не слепо pr:opened: у открытого пул-реквеста уже есть статус ревью, и объявить
            // его только что открытым — значит вернуть карточку, лежащую в «нужны правки»,
            // обратно в колонку ревью.
            return currentReviewEvent(snapshot);
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

        // Новые коммиты важнее смены статуса ревью: пуш всё равно обесценивает ревью.
        if (changed(previous.getLatestCommit(), snapshot.latestCommit())) {
            return "pr:from_ref_updated";
        }

        if (changed(previous.getReviewerDigest(), truncate(snapshot.reviewerDigest()))) {
            if (snapshot.hasChangesRequested()) {
                return "pr:reviewer:changes_requested";
            }
            return snapshot.hasApproval() ? "pr:reviewer:approved" : "pr:reviewer:unapproved";
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
        return null;
    }

    /**
     * Endpoint со списком может не прислать репозиторий в ref'е; карточка ключуется по
     * нему, поэтому подставляем его из конфигурации, а не роняем обработку дальше по цепочке.
     */
    private BitbucketPrEvent.PullRequest withRepository(BitbucketPrEvent.PullRequest pullRequest,
                                                        LifecycleProperties.RepoBoard repo) {
        BitbucketPrEvent.Repository fallback = new BitbucketPrEvent.Repository(
                repo.repoSlug(), repo.repoSlug(), new BitbucketPrEvent.Project(repo.projectKey(), repo.projectKey()));

        BitbucketPrEvent.Ref toRef = pullRequest.toRef();
        BitbucketPrEvent.Ref resolved = toRef == null
                ? new BitbucketPrEvent.Ref(null, null, null, fallback)
                : new BitbucketPrEvent.Ref(toRef.id(), toRef.displayId(), toRef.latestCommit(),
                        toRef.repository() != null ? toRef.repository() : fallback);

        return new BitbucketPrEvent.PullRequest(
                pullRequest.id(), pullRequest.title(), pullRequest.description(), pullRequest.state(),
                pullRequest.version(), pullRequest.updatedDate(), pullRequest.fromRef(), resolved,
                pullRequest.author(), pullRequest.reviewers(), pullRequest.links());
    }

    /** Идентифицирует переход состояния: повторный опрос неизменившегося пул-реквеста даёт дубликат. */
    private String externalId(PullRequestRef ref, String eventKey, BitbucketPrEvent snapshot,
                              BitbucketPrEvent.PullRequest pullRequest, String taskDigest) {
        String fingerprint = String.join("|", ref.asKey(), eventKey,
                String.valueOf(pullRequest.state()), String.valueOf(pullRequest.version()),
                String.valueOf(snapshot.latestCommit()), String.valueOf(snapshot.reviewerDigest()),
                String.valueOf(taskDigest));
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

    /** Где открытому пул-реквесту место прямо сейчас — судя только по его ревьюерам. */
    private String currentReviewEvent(BitbucketPrEvent snapshot) {
        if (snapshot.hasChangesRequested()) {
            return "pr:reviewer:changes_requested";
        }
        return snapshot.hasApproval() ? "pr:reviewer:approved" : "pr:opened";
    }

    private static boolean changed(String previous, String current) {
        return current != null && !Objects.equals(previous, current);
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 64 ? value : value.substring(0, 64);
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
