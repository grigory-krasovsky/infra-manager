package com.example.inframanager.deployment;

import java.util.List;

/**
 * Ради чего был деплой — в порядке убывания полезности для читающего.
 *
 * <p>Задача лучше всего: она названа человеческими словами. Пул-реквест — то, что
 * остаётся, когда в коммитах не нашлось ключа задачи; он хотя бы говорит, что именно
 * уехало. Коммиты — последняя ступень: деплой они не называют, зато точно перечисляют,
 * что в нём изменилось. Пусто всё — значит сказать нечего, и в сообщении останется имя
 * версии Bamboo.
 *
 * @param commits первые строки сообщений коммитов, от свежих к старым
 */
public record DeploymentSubject(List<DeploymentIssue> issues,
                                List<DeploymentPullRequest> pullRequests,
                                List<String> commits) {

    private static final DeploymentSubject EMPTY = new DeploymentSubject(List.of(), List.of(), List.of());

    public DeploymentSubject {
        issues = issues == null ? List.of() : List.copyOf(issues);
        pullRequests = pullRequests == null ? List.of() : List.copyOf(pullRequests);
        commits = commits == null ? List.of() : List.copyOf(commits);
    }

    public static DeploymentSubject empty() {
        return EMPTY;
    }

    /** Пусто — это «о содержимом деплоя не известно ничего»; тогда и нужно имя версии. */
    public boolean isEmpty() {
        return issues.isEmpty() && pullRequests.isEmpty() && commits.isEmpty();
    }
}
