package com.example.inframanager.jira;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Finds a Jira issue key in a branch name or pull request title.
 *
 * <p>Always available, even with Jira disabled: knowing the key is useful on its own
 * and gets recorded on the card link regardless of whether a summary can be fetched.
 */
@Component
public class IssueKeyExtractor {

    private final Pattern pattern;

    public IssueKeyExtractor(JiraProperties properties) {
        this.pattern = Pattern.compile(properties.issueKeyPattern());
    }

    /**
     * Searches the branch name first: it is the more deliberate of the two, while a
     * title can mention an unrelated ticket in passing.
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
