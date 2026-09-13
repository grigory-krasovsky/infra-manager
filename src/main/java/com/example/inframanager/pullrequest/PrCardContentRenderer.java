package com.example.inframanager.pullrequest;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Собирает заголовок и описание карточки Trello для пул-реквеста. Описания карточек
 * Trello рендерят Markdown.
 */
@Component
public class PrCardContentRenderer {

    private final BitbucketProperties properties;

    public PrCardContentRenderer(BitbucketProperties properties) {
        this.properties = properties;
    }

    /** Дата в начале строки: «2026 08 19», «2026-08-19», «19.08.2026» и подобные. */
    private static final Pattern LEADING_DATE = Pattern.compile(
            "^(\\d{4}[-_. ]\\d{2}[-_. ]\\d{2}|\\d{2}[-_. ]\\d{2}[-_. ]\\d{4})[-_.: ]*");

    private static final Pattern LEADING_SEPARATORS = Pattern.compile("^[-_.:\\s]+");

    /**
     * Собирает заголовок карточки.
     *
     * <p>Форма — {@code ПРЕФИКС · [KEY] о чём это}; если ключ задачи неизвестен, его
     * скобки опускаются, а разделитель остаётся: {@code ПРЕФИКС · о чём это} — так
     * префикс везде отделён одинаково.
     * Префикс обозначает репозиторий: одна задача обычно даёт пул-реквест
     * во фронт и в бэк, а на доске смешаны несколько репозиториев. Всё остальное — шум:
     * имена веток несут дату и ключ задачи, и то и другое иначе появилось бы дважды.
     *
     * @param issueKey     ключ Jira, найденный в ветке или заголовке, либо null
     * @param issueSummary как эту задачу называет Jira, либо null, если Jira выключена,
     *                     недоступна или не знает такого ключа
     */
    public String title(BitbucketPrEvent event, LifecycleProperties.RepoBoard repo,
                        String issueKey, String issueSummary) {
        String rawTitle = event.pullRequest() == null || !StringUtils.hasText(event.pullRequest().title())
                ? "(без заголовка)"
                : event.pullRequest().title();

        // Summary из Jira уже нормально описывает задачу; заголовок, выведенный из ветки, —
        // лишь запасной вариант, и из него надо вычистить шум.
        String subject = StringUtils.hasText(issueSummary) ? issueSummary : cleanTitle(rawTitle, issueKey);

        String head = StringUtils.hasText(issueKey)
                ? "%s · [%s]".formatted(repo.displayPrefix(), issueKey)
                : "%s ·".formatted(repo.displayPrefix());
        return "%s %s".formatted(head, subject);
    }

    /**
     * Убирает ведущую дату и ключ задачи из заголовка пул-реквеста, выведенного из имени
     * ветки, — чтобы «2026 08 19 ORVD-1047 BusinessProcess Copy Test» читалось как
     * «BusinessProcess Copy Test».
     */
    static String cleanTitle(String title, String issueKey) {
        String cleaned = LEADING_DATE.matcher(title.trim()).replaceFirst("");
        if (StringUtils.hasText(issueKey)) {
            cleaned = cleaned.replaceAll("(?i)" + Pattern.quote(issueKey), " ");
        }
        cleaned = LEADING_SEPARATORS.matcher(cleaned).replaceFirst("");
        cleaned = cleaned.replaceAll("\\s{2,}", " ").trim();
        // Если вычистилось всё, значит в заголовке были только дата и ключ; оставить
        // оригинал лучше, чем получить карточку без имени.
        return cleaned.isEmpty() ? title.trim() : cleaned;
    }

    public String description(BitbucketPrEvent event, PullRequestRef ref) {
        StringBuilder description = new StringBuilder();

        description.append("[Открыть PR](").append(prUrl(event, ref)).append(")\n\n");
        description.append("**Репозиторий:** ").append(ref.projectKey()).append('/').append(ref.repoSlug());

        if (StringUtils.hasText(event.sourceBranch())) {
            description.append("\n**Ветка:** ").append(event.sourceBranch());
            if (StringUtils.hasText(event.targetBranch())) {
                description.append(" → ").append(event.targetBranch());
            }
        }
        if (StringUtils.hasText(event.authorName())) {
            description.append("\n**Автор:** ").append(event.authorName());
        }
        List<String> reviewers = event.reviewerNames();
        if (!reviewers.isEmpty()) {
            description.append("\n**Ревьюверы:** ").append(String.join(", ", reviewers));
        }
        return description.toString();
    }

    /**
     * Предпочитает ссылку, которую прислал Bitbucket. Иначе собирает её по обычному
     * шаблону пути, потому что адрес, по которому кликает человек, не всегда совпадает
     * с тем, куда ходит контейнер.
     */
    private String prUrl(BitbucketPrEvent event, PullRequestRef ref) {
        return event.selfLink().orElseGet(() -> "%s/projects/%s/repos/%s/pull-requests/%d".formatted(
                properties.effectiveBrowseUrl(), ref.projectKey(), ref.repoSlug(), ref.prId()));
    }
}
