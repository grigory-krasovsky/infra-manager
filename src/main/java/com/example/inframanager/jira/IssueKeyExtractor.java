package com.example.inframanager.jira;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Находит ключ задачи Jira в имени ветки или заголовке пул-реквеста.
 *
 * <p>Доступен всегда, даже при выключенной Jira: знать сам ключ полезно и без summary,
 * и он записывается в связку карточки независимо от того, удалось ли получить summary.
 */
@Component
public class IssueKeyExtractor {

    private final Pattern pattern;

    public IssueKeyExtractor(JiraProperties properties) {
        this.pattern = Pattern.compile(properties.issueKeyPattern());
    }

    /**
     * Сначала ищет в имени ветки: из двух это более осознанное место, тогда как в
     * заголовке между делом может упоминаться посторонний тикет.
     */
    public Optional<String> extract(String branchName, String pullRequestTitle) {
        return firstMatch(branchName).or(() -> firstMatch(pullRequestTitle));
    }

    private Optional<String> firstMatch(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
