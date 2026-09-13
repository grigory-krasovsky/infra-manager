package com.example.inframanager.deployment;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.inframanager.jira.JiraProperties;
import com.example.inframanager.pullrequest.BitbucketProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Выясняет, ради чего был деплой.
 *
 * <p>В результате деплоя этого нет: Bamboo знает лишь собственное имя версии
 * («release-617»), а оно не говорит о содержимом ничего. Зато причина запуска называет
 * сборку («Child of LIZA-APIP-636»), а сборка помнит и связанные задачи — Bamboo сам
 * достаёт их из сообщений коммитов, — и сами коммиты. Получается два шага: из причины —
 * ключ сборки, по сборке — задача, а если задачи нет, то пул-реквест из merge-коммита.
 *
 * <p>Это украшение, и уронить из-за него уведомление было бы неразумно: недоступный
 * Bamboo означает сообщение без строки про задачу, а не потерянное сообщение.
 */
@Component
public class DeploymentSubjectEnricher {

    private static final Logger log = LoggerFactory.getLogger(DeploymentSubjectEnricher.class);

    /** Ключ сборки Bamboo: {@code LIZA-APIP-636}, {@code ORVD-DEV-1169}. */
    private static final Pattern BUILD_KEY = Pattern.compile("\\b[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*-\\d+\\b");

    /**
     * Сообщение merge-коммита, каким его пишет Bitbucket:
     * <pre>
     * Pull request #107: 2026 07 31 init claude code
     * Merge in LIZA/liza from 2026-07-31_init-claude-code to prod
     * </pre>
     * Вторая строка здесь обязательна — она и называет репозиторий, без которого ссылку
     * не собрать. Слитый вручную коммит такой строки не имеет и остаётся незамеченным.
     */
    private static final Pattern MERGE_COMMIT = Pattern.compile(
            "^Pull request #(\\d+):[ \\t]*(.*)\\R+Merge in ([^/\\s]+)/(\\S+) from ", Pattern.MULTILINE);

    /** Больше одной задачи на деплой бывает, но в уведомлении это уже простыня. */
    private static final int MAX_ISSUES = 2;

    /** По той же причине; лишними оказываются самые старые, поэтому режем с начала. */
    private static final int MAX_PULL_REQUESTS = 2;

    /** Оба раздела Bamboo отдаёт только по явному запросу. */
    private static final String EXPAND = "jiraIssues,changes.change";

    private final ObjectProvider<BambooClient> client;
    private final JiraProperties jira;
    private final BitbucketProperties bitbucket;

    public DeploymentSubjectEnricher(ObjectProvider<BambooClient> client,
                                     JiraProperties jira,
                                     BitbucketProperties bitbucket) {
        this.client = client;
        this.jira = jira;
        this.bitbucket = bitbucket;
    }

    public DeploymentSubject subjectFor(BambooDeploymentEvent event) {
        // Клиента может не быть вовсе: он поднимается вместе с опросом, а при работе
        // по вебхукам мы в Bamboo не ходим.
        BambooClient bamboo = client.getIfAvailable();
        if (bamboo == null || event == null) {
            return DeploymentSubject.empty();
        }
        Optional<String> buildKey = buildKey(event.triggerSentence());
        if (buildKey.isEmpty()) {
            return DeploymentSubject.empty();
        }
        try {
            BambooClient.BuildResult result = bamboo.buildResult(buildKey.get(), EXPAND);
            if (result == null) {
                return DeploymentSubject.empty();
            }
            List<DeploymentIssue> issues = issues(result);
            // Пул-реквесты ищем, только когда задач нет: в сообщении они всё равно уступят
            // задаче место, а разбирать коммиты впустую незачем.
            return new DeploymentSubject(issues, issues.isEmpty() ? pullRequests(result) : List.of());
        } catch (Exception e) {
            log.warn("Could not read Bamboo build {}", buildKey.get(), e);
            return DeploymentSubject.empty();
        }
    }

    private List<DeploymentIssue> issues(BambooClient.BuildResult result) {
        return result.issues().stream()
                .filter(issue -> issue != null && StringUtils.hasText(issue.key()))
                .limit(MAX_ISSUES)
                .map(issue -> new DeploymentIssue(issue.key(), issue.summary(), browseUrl(issue.key())))
                .toList();
    }

    /**
     * Коммиты Bamboo отдаёт в хронологическом порядке, так что merge-коммит нужного
     * пул-реквеста — последний, а не первый: обрезать список надо с начала.
     */
    private List<DeploymentPullRequest> pullRequests(BambooClient.BuildResult result) {
        List<DeploymentPullRequest> found = new ArrayList<>();
        for (BambooClient.BuildResult.Change change : result.changeList()) {
            if (change != null) {
                mergedPullRequest(change.comment()).ifPresent(found::add);
            }
        }
        return found.size() <= MAX_PULL_REQUESTS
                ? found
                : found.subList(found.size() - MAX_PULL_REQUESTS, found.size());
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

    /** @return пул-реквест, если это сообщение merge-коммита Bitbucket, иначе пусто */
    private Optional<DeploymentPullRequest> mergedPullRequest(String comment) {
        if (!StringUtils.hasText(comment)) {
            return Optional.empty();
        }
        Matcher matcher = MERGE_COMMIT.matcher(comment);
        if (!matcher.find()) {
            return Optional.empty();
        }
        long id;
        try {
            id = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            // Номер длиннее long — это не пул-реквест, а совпадение по форме.
            return Optional.empty();
        }
        String title = matcher.group(2).trim();
        return Optional.of(new DeploymentPullRequest(
                id, title.isEmpty() ? null : title, pullRequestUrl(matcher.group(3), matcher.group(4), id)));
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

    private String pullRequestUrl(String projectKey, String repoSlug, long id) {
        String base = bitbucket.effectiveBrowseUrl();
        return StringUtils.hasText(base)
                ? "%s/projects/%s/repos/%s/pull-requests/%d".formatted(base, projectKey, repoSlug, id)
                : null;
    }
}
