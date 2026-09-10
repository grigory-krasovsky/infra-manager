package com.example.inframanager.pullrequest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Identifies a pull request. This is the card's natural key -- one PR, one card --
 * so nothing has to be inferred from branch naming conventions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PullRequestRef(String projectKey, String repoSlug, long prId) {

    /** Stable across redeliveries, and short enough for the dedup_key column. */
    public String asKey() {
        return "%s/%s/%d".formatted(projectKey, repoSlug, prId);
    }
}
