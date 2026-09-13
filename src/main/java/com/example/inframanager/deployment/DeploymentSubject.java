package com.example.inframanager.deployment;

import java.util.List;

/**
 * Ради чего был деплой — в порядке убывания полезности для читающего.
 *
 * <p>Задача лучше всего: она названа человеческими словами. Пул-реквест — то, что
 * остаётся, когда в коммитах не нашлось ключа задачи; он хотя бы говорит, что именно
 * уехало. Пусты оба — значит сказать нечего, и в сообщении останется имя версии Bamboo.
 */
public record DeploymentSubject(List<DeploymentIssue> issues, List<DeploymentPullRequest> pullRequests) {

    private static final DeploymentSubject EMPTY = new DeploymentSubject(List.of(), List.of());

    public DeploymentSubject {
        issues = issues == null ? List.of() : List.copyOf(issues);
        pullRequests = pullRequests == null ? List.of() : List.copyOf(pullRequests);
    }

    public static DeploymentSubject empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return issues.isEmpty() && pullRequests.isEmpty();
    }
}
