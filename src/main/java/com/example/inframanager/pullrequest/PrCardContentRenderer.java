package com.example.inframanager.pullrequest;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Builds the title and description of a pull request's Trello card. Trello card
 * descriptions render Markdown.
 */
@Component
public class PrCardContentRenderer {

    private final BitbucketProperties properties;

    public PrCardContentRenderer(BitbucketProperties properties) {
        this.properties = properties;
    }

    /** Leading "2026 08 19", "2026-08-19", "19.08.2026" and similar. */
    private static final Pattern LEADING_DATE = Pattern.compile(
            "^(\\d{4}[-_. ]\\d{2}[-_. ]\\d{2}|\\d{2}[-_. ]\\d{2}[-_. ]\\d{4})[-_.: ]*");

    private static final Pattern LEADING_SEPARATORS = Pattern.compile("^[-_.:\\s]+");

    /**
     * Builds the card title.
     *
     * <p>Shape is {@code PREFIX · [KEY] what it is}, dropping either bracket group
     * when it is unknown. The prefix identifies the repository, since one task
     * routinely produces a front-end and a back-end pull request and the board mixes
     * several repositories. Everything else is noise: branch names carry the date and
     * the issue key, both of which would otherwise appear twice.
     *
     * @param issueKey     Jira key found in the branch or title, or null
     * @param issueSummary what Jira calls that issue, or null when Jira is off,
     *                     unreachable, or does not know the key
     */
    public String title(BitbucketPrEvent event, LifecycleProperties.RepoBoard repo,
                        String issueKey, String issueSummary) {
        String rawTitle = event.pullRequest() == null || !StringUtils.hasText(event.pullRequest().title())
                ? "(без заголовка)"
                : event.pullRequest().title();

        // A Jira summary already describes the task properly; the branch-derived
        // title is only a fallback and needs the noise stripped.
        String subject = StringUtils.hasText(issueSummary) ? issueSummary : cleanTitle(rawTitle, issueKey);

        String head = StringUtils.hasText(issueKey)
                ? "%s · [%s]".formatted(repo.displayPrefix(), issueKey)
                : repo.displayPrefix();
        return "%s %s".formatted(head, subject);
    }

    /**
     * Strips the leading date and the issue key from a branch-derived pull request
     * title, so "2026 08 19 ORVD-1047 BusinessProcess Copy Test" reads as
     * "BusinessProcess Copy Test".
     */
    static String cleanTitle(String title, String issueKey) {
        String cleaned = LEADING_DATE.matcher(title.trim()).replaceFirst("");
        if (StringUtils.hasText(issueKey)) {
            cleaned = cleaned.replaceAll("(?i)" + Pattern.quote(issueKey), " ");
        }
        cleaned = LEADING_SEPARATORS.matcher(cleaned).replaceFirst("");
        cleaned = cleaned.replaceAll("\\s{2,}", " ").trim();
        // Stripping everything means the title was only a date and a key; keeping the
        // original beats an empty card name.
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
     * Prefers the link Bitbucket sent. Falls back to the conventional path, because
     * the browse URL a human clicks is not always the one the container dials.
     */
    private String prUrl(BitbucketPrEvent event, PullRequestRef ref) {
        return event.selfLink().orElseGet(() -> "%s/projects/%s/repos/%s/pull-requests/%d".formatted(
                trimTrailingSlash(properties.effectiveBrowseUrl()),
                ref.projectKey(), ref.repoSlug(), ref.prId()));
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
