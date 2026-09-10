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
 * Достаёт summary задачи, чтобы карточку можно было озаглавить человеческими словами.
 *
 * <p>Строго best-effort. Ни недоступность Jira, ни неверный ключ, ни выключенная Jira
 * не должны мешать создать карточку — это украшение поверх жизненного цикла, в котором
 * Jira не участвует. Любой сбой проглатывается и пишется в лог.
 */
@Component
public class JiraEnricher {

    private static final Logger log = LoggerFactory.getLogger(JiraEnricher.class);

    /**
     * Сбои тоже кешируются, ненадолго: без этого во время аварии каждое событие
     * пул-реквеста превращается в очередной обречённый вызов, пока воркер держит
     * блокировку строки.
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
