package com.example.inframanager.jira;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Looks up an issue summary so a card can be titled in human words.
 *
 * <p>Strictly best-effort. Jira being unreachable, the key being wrong, or Jira being
 * switched off must never stop a card from being created -- this is decoration on top
 * of a lifecycle Jira has no part in. Every failure is swallowed and logged.
 */
@Component
public class JiraEnricher {

    private static final Logger log = LoggerFactory.getLogger(JiraEnricher.class);

    /**
     * Failures are cached too, briefly: without this, an outage turns every pull
     * request event into another doomed call while holding a worker's row lock.
     */
    private static final Duration FAILURE_CACHE_TTL = Duration.ofMinutes(1);

    private final ObjectProvider<JiraClient> clientProvider;
    private final JiraProperties properties;
    private final Map<String, CachedSummary> cache = new ConcurrentHashMap<>();

    public JiraEnricher(ObjectProvider<JiraClient> clientProvider, JiraProperties properties) {
        this.clientProvider = clientProvider;
        this.properties = properties;
    }

    public Optional<String> summaryFor(String issueKey) {
        if (!StringUtils.hasText(issueKey) || !properties.enabled()) {
            return Optional.empty();
        }

        CachedSummary cached = cache.get(issueKey);
        if (cached != null && !cached.isExpired()) {
            return Optional.ofNullable(cached.summary());
        }

        JiraClient client = clientProvider.getIfAvailable();
        if (client == null) {
            return Optional.empty();
        }

        try {
            JiraClient.Issue issue = client.issue(issueKey, "summary");
            String summary = issue == null ? null : issue.summary();
            cache.put(issueKey, new CachedSummary(summary,
                    Instant.now().plus(summary == null ? FAILURE_CACHE_TTL : properties.cacheTtl())));
            return Optional.ofNullable(summary);
        } catch (Exception e) {
            log.warn("Could not read {} from Jira; the card keeps its pull request title", issueKey, e);
            cache.put(issueKey, new CachedSummary(null, Instant.now().plus(FAILURE_CACHE_TTL)));
            return Optional.empty();
        }
    }

    private record CachedSummary(String summary, Instant expiresAt) {

        boolean isExpired() {
            return expiresAt.isBefore(Instant.now());
        }
    }
}
