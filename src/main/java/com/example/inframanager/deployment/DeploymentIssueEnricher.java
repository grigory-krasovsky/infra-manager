package com.example.inframanager.deployment;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.inframanager.jira.JiraProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Находит задачу, ради которой был деплой.
 *
 * <p>В результате деплоя её нет: Bamboo знает лишь собственное имя версии
 * («release-617»), а оно не говорит о содержимом ничего. Зато причина запуска называет
 * сборку («Child of LIZA-APIP-636»), а у сборки Bamboo помнит связанные задачи — он
 * сам достаёт их из сообщений коммитов. Получается два шага: из причины — ключ сборки,
 * по сборке — ключ задачи.
 *
 * <p>Это украшение, и уронить из-за него уведомление было бы неразумно: недоступный
 * Bamboo означает сообщение без строки про задачу, а не потерянное сообщение.
 */
@Component
public class DeploymentIssueEnricher {

    private static final Logger log = LoggerFactory.getLogger(DeploymentIssueEnricher.class);

    /** Ключ сборки Bamboo: {@code LIZA-APIP-636}, {@code ORVD-DEV-1169}. */
    private static final Pattern BUILD_KEY = Pattern.compile("\\b[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*-\\d+\\b");

    /** Больше одной задачи на деплой бывает, но в уведомлении это уже простыня. */
    private static final int MAX_ISSUES = 2;

    private final ObjectProvider<BambooClient> client;
    private final JiraProperties jira;

    public DeploymentIssueEnricher(ObjectProvider<BambooClient> client, JiraProperties jira) {
        this.client = client;
        this.jira = jira;
    }

    public List<DeploymentIssue> issuesFor(BambooDeploymentEvent event) {
        // Клиента может не быть вовсе: он поднимается вместе с опросом, а при работе
        // по вебхукам мы в Bamboo не ходим.
        BambooClient bamboo = client.getIfAvailable();
        if (bamboo == null || event == null) {
            return List.of();
        }
        Optional<String> buildKey = buildKey(event.triggerSentence());
        if (buildKey.isEmpty()) {
            return List.of();
        }
        try {
            BambooClient.BuildResult result = bamboo.buildResult(buildKey.get(), "jiraIssues");
            return result == null ? List.of() : result.issues().stream()
                    .filter(issue -> issue != null && StringUtils.hasText(issue.key()))
                    .limit(MAX_ISSUES)
                    .map(issue -> new DeploymentIssue(issue.key(), issue.summary(), browseUrl(issue.key())))
                    .toList();
        } catch (Exception e) {
            log.warn("Could not read Jira issues of Bamboo build {}", buildKey.get(), e);
            return List.of();
        }
    }

    /**
     * @return ключ сборки из причины запуска — «Child of <a href="…">LIZA-APIP-636</a>».
     *         Пусто у ручного запуска и у всего, где сборки не было.
     */
    static Optional<String> buildKey(String triggerSentence) {
        if (!StringUtils.hasText(triggerSentence)) {
            return Optional.empty();
        }
        Matcher matcher = BUILD_KEY.matcher(triggerSentence);
        return matcher.find() ? Optional.of(matcher.group()) : Optional.empty();
    }

    /**
     * Ссылку строим из настроенного адреса, а не берём ту, что отдаёт Bamboo: у неё
     * хвост {@code ?page=com.atlassian…bamboo-build-results-tabpanel}, открывающий
     * задачу на вкладке сборок.
     */
    private String browseUrl(String issueKey) {
        String base = jira.browseUrlOrBase();
        return StringUtils.hasText(base) ? base + "/browse/" + issueKey : null;
    }
}
