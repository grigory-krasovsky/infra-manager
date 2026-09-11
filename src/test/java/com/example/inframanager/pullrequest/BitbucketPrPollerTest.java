package com.example.inframanager.pullrequest;

import java.util.List;

import com.example.inframanager.TestcontainersConfiguration;
import com.example.inframanager.event.InboundEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Путь с опросом вынужден восстанавливать события, о которых вебхук сообщил бы прямо,
 * поэтому важно, какой ключ события получается на каждом переходе.
 */
@SpringBootTest(properties = {
        "infra-manager.workers.scheduling-enabled=false",
        "infra-manager.bitbucket.poll.enabled=true",
        "infra-manager.bitbucket.base-url=https://bitbucket.invalid",
        "infra-manager.bitbucket.token=test-token",
        "infra-manager.lifecycle.repos[0].project-key=LIZA",
        "infra-manager.lifecycle.repos[0].repo-slug=liza",
        "infra-manager.lifecycle.repos[0].trello-board-id=board-1"
})
@Import(TestcontainersConfiguration.class)
class BitbucketPrPollerTest {

    @Autowired
    private BitbucketPrPoller poller;
    @Autowired
    private InboundEventRepository inboundEvents;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private BitbucketClient client;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE inbound_event, outbound_task, pr_poll_state, pr_card_link RESTART IDENTITY");
    }

    @Test
    void anOpenPullRequestSeenForTheFirstTimeIsTreatedAsJustOpened() {
        given(pr(1, "OPEN", 1, "commit-a", List.of()));

        assertThat(poller.runOnce()).isEqualTo(1);

        assertThat(eventTypes()).containsExactly("pr:opened");
    }

    @Test
    void aClosedPullRequestSeenForTheFirstTimeRaisesNothing() {
        // Иначе первый же опрос активного репозитория объявит всю его историю.
        given(pr(2, "MERGED", 5, "commit-a", List.of()));

        assertThat(poller.runOnce()).isZero();
        assertThat(inboundEvents.count()).isZero();
    }

    @Test
    void anUnchangedPullRequestRaisesNothingOnTheNextPass() {
        given(pr(3, "OPEN", 1, "commit-a", List.of()));
        poller.runOnce();

        assertThat(poller.runOnce()).isZero();
        assertThat(eventTypes()).containsExactly("pr:opened");
    }

    @Test
    void mergingIsSeenAsAMerge() {
        given(pr(4, "OPEN", 1, "commit-a", List.of()));
        poller.runOnce();

        given(pr(4, "MERGED", 2, "commit-a", List.of()));
        assertThat(poller.runOnce()).isEqualTo(1);

        assertThat(eventTypes()).containsExactly("pr:opened", "pr:merged");
    }

    @Test
    void decliningIsSeenAsADecline() {
        given(pr(5, "OPEN", 1, "commit-a", List.of()));
        poller.runOnce();

        given(pr(5, "DECLINED", 2, "commit-a", List.of()));
        poller.runOnce();

        assertThat(eventTypes()).containsExactly("pr:opened", "pr:declined");
    }

    @Test
    void aNewHeadCommitIsSeenAsAPush() {
        given(pr(6, "OPEN", 1, "commit-a", List.of()));
        poller.runOnce();

        given(pr(6, "OPEN", 2, "commit-b", List.of()));
        poller.runOnce();

        assertThat(eventTypes()).containsExactly("pr:opened", "pr:from_ref_updated");
    }

    @Test
    void anApprovalIsSeenAsAnApproval() {
        given(pr(7, "OPEN", 1, "commit-a", List.of(reviewer("ivan", "UNAPPROVED", false))));
        poller.runOnce();

        given(pr(7, "OPEN", 1, "commit-a", List.of(reviewer("ivan", "APPROVED", true))));
        poller.runOnce();

        assertThat(eventTypes()).containsExactly("pr:opened", "pr:reviewer:approved");
    }

    @Test
    void needsWorkIsSeenAsChangesRequested() {
        given(pr(8, "OPEN", 1, "commit-a", List.of(reviewer("ivan", "UNAPPROVED", false))));
        poller.runOnce();

        given(pr(8, "OPEN", 1, "commit-a", List.of(reviewer("ivan", "NEEDS_WORK", false))));
        poller.runOnce();

        assertThat(eventTypes()).containsExactly("pr:opened", "pr:reviewer:changes_requested");
    }

    @Test
    void anEditWithNothingElseChangedIsSeenAsAModification() {
        given(pr(9, "OPEN", 1, "commit-a", List.of()));
        poller.runOnce();

        given(pr(9, "OPEN", 2, "commit-a", List.of()));
        poller.runOnce();

        assertThat(eventTypes()).containsExactly("pr:opened", "pr:modified");
    }

    @Test
    void aPushOutranksASimultaneousReviewChange() {
        // Пуш всё равно обесценивает ревью, так что карточке место обратно в ревью.
        given(pr(10, "OPEN", 1, "commit-a", List.of(reviewer("ivan", "APPROVED", true))));
        poller.runOnce();

        given(pr(10, "OPEN", 2, "commit-b", List.of(reviewer("ivan", "UNAPPROVED", false))));
        poller.runOnce();

        // При первой встрече сообщается об апруве, а не о простом открытии — см. тесты ниже.
        assertThat(eventTypes()).containsExactly("pr:reviewer:approved", "pr:from_ref_updated");
    }

    @Test
    void firstSightOfAnAlreadyApprovedPullRequestReportsTheApproval() {
        // Иначе перезапуск — или любой сброс снимков — утащил бы каждую открытую карточку
        // обратно в колонку ревью независимо от того, до чего ревью успело дойти.
        given(pr(20, "OPEN", 1, "commit-a", List.of(reviewer("ivan", "APPROVED", true))));

        poller.runOnce();

        assertThat(eventTypes()).containsExactly("pr:reviewer:approved");
    }

    @Test
    void firstSightOfAPullRequestNeedingWorkReportsChangesRequested() {
        given(pr(21, "OPEN", 1, "commit-a", List.of(reviewer("ivan", "NEEDS_WORK", false))));

        poller.runOnce();

        assertThat(eventTypes()).containsExactly("pr:reviewer:changes_requested");
    }

    @Test
    void firstSightOfAnUnreviewedPullRequestIsStillJustOpened() {
        given(pr(22, "OPEN", 1, "commit-a", List.of(reviewer("ivan", "UNAPPROVED", false))));

        poller.runOnce();

        assertThat(eventTypes()).containsExactly("pr:opened");
    }

    @Test
    void anOpenTaskIsSeenAsARequestToChangeSomething() {
        // Кнопку «Needs work» жмут не всегда: ревьюер ставит задачи, статус при этом
        // остаётся UNAPPROVED, а мерж всё равно заблокирован.
        given(pr(30, "OPEN", 1, "commit-a", List.of(reviewer("ivan", "UNAPPROVED", false))));
        poller.runOnce();

        givenOpenTasks(30, 3);
        assertThat(poller.runOnce()).isEqualTo(1);

        assertThat(eventTypes()).containsExactly("pr:opened", "pr:reviewer:changes_requested");
    }

    @Test
    void closingTheLastTaskSendsThePullRequestBackToReview() {
        given(pr(31, "OPEN", 1, "commit-a", List.of(reviewer("ivan", "UNAPPROVED", false))));
        givenOpenTasks(31, 2);
        poller.runOnce();

        givenOpenTasks(31, 0);
        poller.runOnce();

        assertThat(eventTypes()).containsExactly("pr:reviewer:changes_requested", "pr:reviewer:unapproved");
    }

    @Test
    void anOpenTaskOutweighsAnApproval() {
        // Влить всё равно нельзя, так что «Аппрув» был бы неправдой.
        given(pr(32, "OPEN", 1, "commit-a", List.of(reviewer("ivan", "APPROVED", true))));
        givenOpenTasks(32, 1);

        poller.runOnce();

        assertThat(eventTypes()).containsExactly("pr:reviewer:changes_requested");
    }

    @Test
    void aTaskCountThatDidNotChangeRaisesNothing() {
        given(pr(33, "OPEN", 1, "commit-a", List.of()));
        givenOpenTasks(33, 2);
        poller.runOnce();

        assertThat(poller.runOnce()).isZero();
        assertThat(eventTypes()).containsExactly("pr:reviewer:changes_requested");
    }

    @Test
    void tasksAreNotAskedAboutForClosedPullRequests() {
        given(pr(34, "MERGED", 1, "commit-a", List.of()));

        poller.runOnce();

        verify(client, never()).blockerComments(anyString(), anyString(), anyLong(), anyBoolean());
    }

    @Test
    void anUnreadableTaskCountLeavesTheLastKnownOneAlone() {
        // Выдумать «задач нет» значило бы утащить карточку из «запрошены изменения»
        // обратно в ревью на первой же заминке Bitbucket.
        given(pr(35, "OPEN", 1, "commit-a", List.of()));
        givenOpenTasks(35, 2);
        poller.runOnce();

        when(client.blockerComments(anyString(), anyString(), eq(35L), anyBoolean()))
                .thenThrow(new IllegalStateException("bitbucket is down"));

        assertThat(poller.runOnce()).isZero();
        assertThat(eventTypes()).containsExactly("pr:reviewer:changes_requested");
    }

    @Test
    void theSyntheticPayloadCarriesEnoughToIdentifyTheRepository() {
        given(pr(11, "OPEN", 1, "commit-a", List.of()));
        poller.runOnce();

        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM inbound_event LIMIT 1", String.class);
        assertThat(payload).contains("LIZA").contains("liza");
    }

    @Test
    void anUnreachableRepositoryDoesNotBreakThePass() {
        when(client.pullRequests(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("bitbucket is down"));

        assertThat(poller.runOnce()).isZero();
    }

    private void given(BitbucketPrEvent.PullRequest... pullRequests) {
        when(client.pullRequests(eq("LIZA"), eq("liza"), anyString(), anyString(), anyInt()))
                .thenReturn(new BitbucketClient.PullRequestPage(List.of(pullRequests)));
    }

    private void givenOpenTasks(long prId, int open) {
        when(client.blockerComments(eq("LIZA"), eq("liza"), eq(prId), anyBoolean()))
                .thenReturn(new BitbucketClient.BlockerComments(open));
    }

    private List<String> eventTypes() {
        return jdbc.queryForList("SELECT event_type FROM inbound_event ORDER BY id", String.class);
    }

    private BitbucketPrEvent.Reviewer reviewer(String name, String status, boolean approved) {
        return new BitbucketPrEvent.Reviewer(
                new BitbucketPrEvent.User(name, name), status, approved);
    }

    private BitbucketPrEvent.PullRequest pr(long id, String state, int version,
                                            String latestCommit,
                                            List<BitbucketPrEvent.Reviewer> reviewers) {
        BitbucketPrEvent.Repository repository = new BitbucketPrEvent.Repository(
                "liza", "liza", new BitbucketPrEvent.Project("LIZA", "LIZA"));
        return new BitbucketPrEvent.PullRequest(
                id, "Fix the thing", "description", state, version, null,
                new BitbucketPrEvent.Ref("refs/heads/feature", "feature", latestCommit, repository),
                new BitbucketPrEvent.Ref("refs/heads/main", "main", "commit-main", repository),
                new BitbucketPrEvent.Author(new BitbucketPrEvent.User("kras", "Grigory")),
                reviewers,
                null);
    }
}
