package com.example.inframanager.deployment;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import com.example.inframanager.jira.JiraProperties;
import com.example.inframanager.pullrequest.BitbucketProperties;
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
 * Поиск того, ради чего был деплой. Интересно, что происходит, когда цепочка «причина
 * запуска → сборка → задача» где-нибудь обрывается: уведомление должно пережить любой
 * обрыв, а на месте задачи — оказаться пул-реквест, если он в коммитах есть.
 */
class DeploymentSubjectEnricherTest {

    /** Ровно то, что пишет Bitbucket, вливая пул-реквест. */
    private static final String MERGE_COMMIT = """
            Pull request #107: 2026 07 31 init claude code
            Merge in LIZA/liza from 2026-07-31_init-claude-code to prod

            * commit '3e65a3a183d883ed4624e327dc53868da85350c3':
              Claude now knows about sibling backend repo""";

    private final BambooClient client = mock(BambooClient.class);

    @Test
    void findsTheIssueBehindTheBuildThatTriggeredTheDeployment() {
        givenBuild("LIZA-APIP-636", new BambooClient.BuildResult.Issue("LIZA-599", "Реализация изменения тэга"));

        assertThat(enricher().subjectFor(event(
                "Child of <a href=\"https://bamboo.local/browse/LIZA-APIP-636\">LIZA-APIP-636</a>")).issues())
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

        assertThat(enricher("https://jira.local/", "https://bitbucket.local/")
                .subjectFor(event("Child of ORVD-DEV-1169")).issues())
                .singleElement()
                .satisfies(issue -> assertThat(issue.url()).isEqualTo("https://jira.local/browse/ORVD-1657"));
    }

    @Test
    void aManualRunHasNoBuildAndSoNothingToName() {
        assertThat(enricher().subjectFor(event("Manual run by krasovsky")).isEmpty()).isTrue();
        verify(client, never()).buildResult(any(), any());
    }

    @Test
    void aBuildWithoutLinkedIssuesJustYieldsNothing() {
        when(client.buildResult(eq("LIZA-APIP-636"), any()))
                .thenReturn(new BambooClient.BuildResult(null, null));

        assertThat(enricher().subjectFor(event("Child of LIZA-APIP-636")).isEmpty()).isTrue();
    }

    @Test
    void anUnreachableBambooCostsTheLineAndNotTheNotification() {
        when(client.buildResult(any(), any())).thenThrow(new IllegalStateException("bamboo is down"));

        assertThat(enricher().subjectFor(event("Child of LIZA-APIP-636")).isEmpty()).isTrue();
    }

    @Test
    void withoutABambooClientTheresNothingToAsk() {
        // Клиент поднимается вместе с опросом; при работе по вебхукам его нет вовсе.
        DeploymentSubjectEnricher enricher = new DeploymentSubjectEnricher(
                provider(null), jira("https://jira.local"), bitbucket("https://bitbucket.local"));

        assertThat(enricher.subjectFor(event("Child of LIZA-APIP-636")).isEmpty()).isTrue();
    }

    @Test
    void severalIssuesAreCappedSoTheMessageStaysReadable() {
        givenBuild("LIZA-APIP-636",
                new BambooClient.BuildResult.Issue("LIZA-1", "one"),
                new BambooClient.BuildResult.Issue("LIZA-2", "two"),
                new BambooClient.BuildResult.Issue("LIZA-3", "three"));

        assertThat(enricher().subjectFor(event("Child of LIZA-APIP-636")).issues())
                .hasSize(2)
                .extracting(DeploymentIssue::key).containsExactly("LIZA-1", "LIZA-2");
    }

    @Test
    void withoutAnIssueTheMergedPullRequestTakesItsPlace() {
        // Так и выглядят деплои Лизы: в коммитах нет ключа задачи, зато влиты они
        // пул-реквестом, и он говорит, что именно уехало.
        givenChanges("LIZA-APPP-437", "Claude now knows about sibling backend repo", MERGE_COMMIT);

        DeploymentSubject subject = enricher().subjectFor(event("Child of LIZA-APPP-437"));

        assertThat(subject.issues()).isEmpty();
        assertThat(subject.pullRequests()).singleElement().satisfies(pr -> {
            assertThat(pr.id()).isEqualTo(107);
            assertThat(pr.title()).isEqualTo("2026 07 31 init claude code");
            assertThat(pr.url())
                    .isEqualTo("https://bitbucket.local/projects/LIZA/repos/liza/pull-requests/107");
        });
    }

    @Test
    void anIssueLeavesThePullRequestUnasked() {
        // Задача сказана человеческими словами, пул-реквест ей в сообщении только мешал бы.
        when(client.buildResult(eq("LIZA-APIP-636"), any())).thenReturn(new BambooClient.BuildResult(
                new BambooClient.BuildResult.JiraIssues(
                        List.of(new BambooClient.BuildResult.Issue("LIZA-599", "Реализация"))),
                new BambooClient.BuildResult.Changes(
                        List.of(new BambooClient.BuildResult.Change(MERGE_COMMIT)))));

        DeploymentSubject subject = enricher().subjectFor(event("Child of LIZA-APIP-636"));

        assertThat(subject.issues()).hasSize(1);
        assertThat(subject.pullRequests()).isEmpty();
    }

    @Test
    void ordinaryCommitsAreNotMistakenForPullRequests() {
        givenChanges("LIZA-APPP-437",
                "Add git push prohibition for Claude",
                "Merge branch 'prod' into 2026-07-29_claude-setup",
                // Ссылка на пул-реквест в тексте коммита — ещё не влитый пул-реквест:
                // репозитория она не называет, а без него ссылку не собрать.
                "Fixes what Pull request #99 broke");

        assertThat(enricher().subjectFor(event("Child of LIZA-APPP-437")).pullRequests()).isEmpty();
    }

    @Test
    void ofSeveralMergesTheLatestOnesAreKept() {
        // Bamboo отдаёт коммиты по возрастанию времени, так что интересны последние.
        givenChanges("LIZA-APPP-437",
                mergeCommit(1, "самый старый"),
                mergeCommit(2, "средний"),
                mergeCommit(3, "самый свежий"));

        assertThat(enricher().subjectFor(event("Child of LIZA-APPP-437")).pullRequests())
                .extracting(DeploymentPullRequest::id).containsExactly(2L, 3L);
    }

    @Test
    void withoutABitbucketAddressThePullRequestKeepsItsNumber() {
        givenChanges("LIZA-APPP-437", MERGE_COMMIT);

        assertThat(enricher("https://jira.local", "").subjectFor(event("Child of LIZA-APPP-437")).pullRequests())
                .singleElement()
                .satisfies(pr -> {
                    assertThat(pr.id()).isEqualTo(107);
                    assertThat(pr.url()).isNull();
                });
    }

    @Test
    void buildKeysAreRecognisedInBothShapesBambooUses() {
        assertThat(DeploymentSubjectEnricher.buildKey("Child of LIZA-APIP-636")).contains("LIZA-APIP-636");
        assertThat(DeploymentSubjectEnricher.buildKey("Child of ORVD-DEV-1169")).contains("ORVD-DEV-1169");
        assertThat(DeploymentSubjectEnricher.buildKey("Scheduled")).isEmpty();
        assertThat(DeploymentSubjectEnricher.buildKey(null)).isEmpty();
    }

    private static String mergeCommit(long id, String title) {
        return "Pull request #%d: %s\nMerge in LIZA/liza from feature to prod".formatted(id, title);
    }

    private void givenBuild(String buildKey, BambooClient.BuildResult.Issue... issues) {
        when(client.buildResult(eq(buildKey), any()))
                .thenReturn(new BambooClient.BuildResult(
                        new BambooClient.BuildResult.JiraIssues(List.of(issues)), null));
    }

    private void givenChanges(String buildKey, String... comments) {
        when(client.buildResult(eq(buildKey), any()))
                .thenReturn(new BambooClient.BuildResult(null, new BambooClient.BuildResult.Changes(
                        Arrays.stream(comments).map(BambooClient.BuildResult.Change::new).toList())));
    }

    private DeploymentSubjectEnricher enricher() {
        return enricher("https://jira.local", "https://bitbucket.local");
    }

    private DeploymentSubjectEnricher enricher(String jiraBrowseUrl, String bitbucketBrowseUrl) {
        return new DeploymentSubjectEnricher(
                provider(client), jira(jiraBrowseUrl), bitbucket(bitbucketBrowseUrl));
    }

    private static BambooDeploymentEvent event(String trigger) {
        return new BambooDeploymentEvent(1L, "SUCCESS", "FINISHED", "Лиза API", null, "prod",
                "release-617", null, null, trigger);
    }

    private static JiraProperties jira(String browseUrl) {
        return new JiraProperties(false, "", "", Duration.ofSeconds(2), Duration.ofSeconds(3),
                Duration.ofHours(1), "([A-Z][A-Z0-9]+-\\d+)", browseUrl);
    }

    private static BitbucketProperties bitbucket(String browseUrl) {
        return new BitbucketProperties(false, "", browseUrl, "", "",
                Duration.ofSeconds(5), Duration.ofSeconds(15),
                new BitbucketProperties.Poll(false, Duration.ofMinutes(2), 50));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<BambooClient> provider(BambooClient client) {
        ObjectProvider<BambooClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }
}
