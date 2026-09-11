package com.example.inframanager.pullrequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Читает задачи пул-реквеста — то, что в Bitbucket называется блокирующими
 * комментариями, а в интерфейсе выглядит списком «tasks».
 *
 * <p>Задачи никуда не двигают карточку: колонка определяется статусом ревьюера и только
 * им. Они попадают в карточку чек-листом, чтобы было видно, что именно просили и что из
 * этого уже закрыто.
 *
 * <p>Различает «задач нет» и «спросить не удалось»: в первом случае чек-лист надо
 * очистить, во втором — не трогать вовсе. Иначе недоступный на минуту Bitbucket стирал
 * бы с карточек весь список замечаний.
 */
@Component
public class PrTaskReader {

    private static final Logger log = LoggerFactory.getLogger(PrTaskReader.class);

    /** Задач на пул-реквест бывает много, но не сотни. */
    private static final int LIMIT = 100;

    private final ObjectProvider<BitbucketClient> client;

    public PrTaskReader(ObjectProvider<BitbucketClient> client) {
        this.client = client;
    }

    /**
     * @return задачи пул-реквеста либо {@code empty}, если спросить не удалось или
     *         клиента Bitbucket в этой конфигурации нет вовсе
     */
    public Optional<List<PrTask>> tasks(PullRequestRef ref) {
        // Клиент поднимается вместе с опросом: при работе по вебхукам в Bitbucket мы
        // не ходим, и задач попросту неоткуда взять.
        BitbucketClient bitbucket = client.getIfAvailable();
        if (bitbucket == null) {
            return Optional.empty();
        }
        try {
            BitbucketClient.BlockerComments comments =
                    bitbucket.blockerComments(ref.projectKey(), ref.repoSlug(), ref.prId(), LIMIT);
            return Optional.of(comments == null ? List.of() : comments.tasks().stream()
                    .filter(task -> task != null)
                    .map(task -> new PrTask(task.id(), task.text(), task.isResolved()))
                    .toList());
        } catch (Exception e) {
            log.warn("Could not read tasks of {}", ref.asKey(), e);
            return Optional.empty();
        }
    }

    /**
     * Отпечаток списка задач: по нему опрос замечает, что задачу добавили, закрыли или
     * удалили. Само по себе это не происходит ни при смене версии пул-реквеста, ни при
     * пуше, ни при смене статуса ревьюера — сравнивать больше не с чем.
     */
    public static String digest(List<PrTask> tasks) {
        String joined = tasks.stream()
                .map(task -> task.id() + "=" + (task.resolved() ? "R" : "O"))
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        return sha256(joined);
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
