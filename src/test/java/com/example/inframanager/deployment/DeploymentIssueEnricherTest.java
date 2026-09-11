package com.example.inframanager.deployment;

import java.time.Duration;
import java.util.List;

import com.example.inframanager.jira.JiraProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Поиск задачи по деплою. Интересно, что происходит, когда цепочка «причина запуска →
 * сборка → задача» где-нибудь обрывается: уведомление должно пережить любой обрыв.
 */
class DeploymentIssueEnricherTest {

    private final BambooClient client = mock(BambooClient.class);

    @Test
    void findsTheIssueBehindTheBuildThatTriggeredTheDeployment() {
        givenBuild("LIZA-APIP-636", new BambooClient.BuildResult.Issue("LIZA-599", "Реализация изменения тэга"));

        assertThat(enricher("https://jira.local").issuesFor(event(
                "Child of <a href=\"https://bamboo.local/browse/LIZA-APIP-636\">LIZA-APIP-636</a>")))
                .singleElement()
                .satisfies(issue -> {
                    assertThat(issue.key()).isEqualTo("LIZA-599");
                    assertThat(issue.summary()).isEqualTo("Реализация изменения тэга");
                    assertThat(issue.url()).isEqualTo("https://jira.local/browse/LIZA-599");
                });
    }

    @Test
    void aTrailingSlashInTheConfiguredAddressDoesNotDoubleUp() {
        givenBuild("ORVD-DEV-1169", new BambooClient.BuildResult.Issue("ORVD-1657", "Фильтр ЭДО"));

        assertThat(enricher("https://jira.local/").issuesFor(event("Child of ORVD-DEV-1169")))
                .singleElement()
                .satisfies(issue -> assertThat(issue.url()).isEqualTo("https://jira.local/browse/ORVD-1657"));
    }

    @Test
    void aManualRunHasNoBuildAndSoNoIssue() {
        assertThat(enricher("https://jira.local").issuesFor(event("Manual run by krasovsky"))).isEmpty();
        verify(client, never()).buildResult(any(), any());
    }

    @Test
    void aBuildWithoutLinkedIssuesJustYieldsNothing() {
        when(client.buildResult(eq("LIZA-APIP-636"), any()))
                .thenReturn(new BambooClient.BuildResult(null));

        assertThat(enricher("https://jira.local").issuesFor(event("Child of LIZA-APIP-636"))).isEmpty();
    }

    @Test
    void anUnreachableBambooCostsTheLineAndNotTheNotification() {
        when(client.buildResult(any(), any())).thenThrow(new IllegalStateException("bamboo is down"));

        assertThat(enricher("https://jira.local").issuesFor(event("Child of LIZA-APIP-636"))).isEmpty();
    }

    @Test
    void withoutABambooClientTheresNothingToAsk() {
        // Клиент поднимается вместе с опросом; при работе по вебхукам его нет вовсе.
        DeploymentIssueEnricher enricher = new DeploymentIssueEnricher(provider(null), jira("https://jira.local"));

        assertThat(enricher.issuesFor(event("Child of LIZA-APIP-636"))).isEmpty();
    }

    @Test
    void severalIssuesAreCappedSoTheMessageStaysReadable() {
        givenBuild("LIZA-APIP-636",
                new BambooClient.BuildResult.Issue("LIZA-1", "one"),
                new BambooClient.BuildResult.Issue("LIZA-2", "two"),
                new BambooClient.BuildResult.Issue("LIZA-3", "three"));

        assertThat(enricher("https://jira.local").issuesFor(event("Child of LIZA-APIP-636")))
                .hasSize(2)
                .extracting(DeploymentIssue::key).containsExactly("LIZA-1", "LIZA-2");
    }

    @Test
    void buildKeysAreRecognisedInBothShapesBamboExUses() {
        assertThat(DeploymentIssueEnricher.buildKey("Child of LIZA-APIP-636")).contains("LIZA-APIP-636");
        assertThat(DeploymentIssueEnricher.buildKey("Child of ORVD-DEV-1169")).contains("ORVD-DEV-1169");
        assertThat(DeploymentIssueEnricher.buildKey("Scheduled")).isEmpty();
        assertThat(DeploymentIssueEnricher.buildKey(null)).isEmpty();
    }

    private void givenBuild(String buildKey, BambooClient.BuildResult.Issue... issues) {
        when(client.buildResult(eq(buildKey), any()))
                .thenReturn(new BambooClient.BuildResult(
                        new BambooClient.BuildResult.JiraIssues(List.of(issues))));
    }

    private DeploymentIssueEnricher enricher(String browseUrl) {
        return new DeploymentIssueEnricher(provider(client), jira(browseUrl));
    }

    private static BambooDeploymentEvent event(String trigger) {
        return new BambooDeploymentEvent(1L, "SUCCESS", "FINISHED", "Лиза API", null, "prod",
                "release-617", null, null, trigger);
    }

    private static JiraProperties jira(String browseUrl) {
        return new JiraProperties(false, "", "", Duration.ofSeconds(2), Duration.ofSeconds(3),
                Duration.ofHours(1), "([A-Z][A-Z0-9]+-\\d+)", browseUrl);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<BambooClient> provider(BambooClient client) {
        ObjectProvider<BambooClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }
}
