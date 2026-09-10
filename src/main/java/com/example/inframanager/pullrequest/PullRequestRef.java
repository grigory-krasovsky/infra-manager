package com.example.inframanager.pullrequest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Идентифицирует пул-реквест. Это естественный ключ карточки — один PR, одна карточка, —
 * поэтому ничего не приходится выводить из соглашений об именовании веток.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PullRequestRef(String projectKey, String repoSlug, long prId) {

    /** Не меняется при повторных доставках и достаточно короток для колонки dedup_key. */
    public String asKey() {
        return "%s/%s/%d".formatted(projectKey, repoSlug, prId);
    }
}
