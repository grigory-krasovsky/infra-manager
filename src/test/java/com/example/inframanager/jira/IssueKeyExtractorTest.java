package com.example.inframanager.jira;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IssueKeyExtractorTest {

    private final IssueKeyExtractor extractor = new IssueKeyExtractor(properties("([A-Z][A-Z0-9]+-\\d+)"));

    @Test
    void findsTheKeyInAConventionalBranchName() {
        assertThat(extractor.extract("feature/PROJ-123-fix-the-thing", "Fix the thing"))
                .contains("PROJ-123");
    }

    @Test
    void fallsBackToTheTitleWhenTheBranchHasNoKey() {
        assertThat(extractor.extract("hotfix/urgent", "PROJ-456: patch the leak"))
                .contains("PROJ-456");
    }

    @Test
    void prefersTheBranchOverTheTitle() {
        // В заголовке между делом может упоминаться посторонний тикет; имя ветки —
        // более осознанное высказывание о том, что за работа.
        assertThat(extractor.extract("feature/PROJ-1-real", "Follow-up to PROJ-999"))
                .contains("PROJ-1");
    }

    @Test
    void returnsNothingWhenNeitherCarriesAKey() {
        assertThat(extractor.extract("dependabot/bump-lib", "Bump lib from 1 to 2")).isEmpty();
    }

    @Test
    void toleratesMissingInput() {
        assertThat(extractor.extract(null, null)).isEmpty();
        assertThat(extractor.extract("", "  ")).isEmpty();
    }

    @Test
    void aTightenedPatternIgnoresKeysFromOtherProjects() {
        IssueKeyExtractor onlyInfra = new IssueKeyExtractor(properties("(INFRA-\\d+)"));

        assertThat(onlyInfra.extract("feature/OTHER-5-thing", null)).isEmpty();
        assertThat(onlyInfra.extract("feature/INFRA-5-thing", null)).contains("INFRA-5");
    }

    private JiraProperties properties(String pattern) {
        return new JiraProperties(false, "", "", Duration.ofSeconds(2), Duration.ofSeconds(3),
                Duration.ofHours(1), pattern, "");
    }
}
