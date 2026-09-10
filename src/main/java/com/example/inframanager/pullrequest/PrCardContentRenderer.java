package com.example.inframanager.pullrequest;

import java.util.List;

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

    /**
     * @param issueKey     Jira key found in the branch or title, or null
     * @param issueSummary what Jira calls that issue, or null when Jira is off,
     *                     unreachable, or does not know the key
     */
    public String title(BitbucketPrEvent event, PullRequestRef ref, String issueKey, String issueSummary) {
        String prTitle = event.pullRequest() == null || !StringUtils.hasText(event.pullRequest().title())
                ? "(без заголовка)"
                : event.pullRequest().title();

        if (StringUtils.hasText(issueKey) && StringUtils.hasText(issueSummary)) {
            // The PR number stays in the title because a card is a pull request, not
            // an issue: two pull requests for one issue must not look identical.
            return "[%s] %s (PR #%d)".formatted(issueKey, issueSummary, ref.prId());
        }
        if (StringUtils.hasText(issueKey)) {
            return "[%s] PR #%d · %s".formatted(issueKey, ref.prId(), prTitle);
        }
        return "PR #%d · %s".formatted(ref.prId(), prTitle);
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
