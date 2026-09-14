package com.example.inframanager.pullrequest;

import java.util.List;

import com.example.inframanager.TestcontainersConfiguration;
import com.example.inframanager.event.InboundEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    void aPushOutranksAReviewVerdictLeftBehindByIt() {
        // Пуш обесценивает ревью, которое было до него, так что карточке место снова в ревью.
        given(pr(10, "OPEN", 1, "commit-a", List.of(reviewer("ivan", "APPROVED", true))));
        poller.runOnce();

        given(pr(10, "OPEN", 2, "commit-b", List.of(reviewer("ivan", "UNAPPROVED", false))));
        poller.runOnce();

        // При первой встрече сообщается об апруве, а не о простом открытии — см. тесты ниже.
        assertThat(eventTypes()).containsExactly("pr:reviewer:approved", "pr:from_ref_updated");
    }

    @Test
    void aSecondNeedsWorkOnTheNewHeadTakesTheCardBack() {
        // Тот самый цикл, ради которого в выжимку и добавлен коммит: статус ревьюера всё
        // это время один и тот же NEEDS_WORK, снимать его никто не приучен.
        given(pr(40, "OPEN", 1, "commit-a",
                List.of(reviewer("ivan", "NEEDS_WORK", false, "commit-a"))));
        poller.runOnce();

        // Автор починил: вердикт остался прежним, но сказан он про уже переписанный код.
        given(pr(40, "OPEN", 2, "commit-b",
                List.of(reviewer("ivan", "NEEDS_WORK", false, "commit-a"))));
        poller.runOnce();

        // Ревьюер посмотрел новое и снова просит доработок.
        given(pr(40, "OPEN", 2, "commit-b",
                List.of(reviewer("ivan", "NEEDS_WORK", false, "commit-b"))));
        poller.runOnce();

        assertThat(eventTypes()).containsExactly(
                "pr:reviewer:changes_requested", "pr:from_ref_updated", "pr:reviewer:changes_requested");
    }

    @Test
    void anApprovalOfTheNewHeadSurvivesASimultaneousPush() {
        // Пуш и апрув укладываются в один проход опроса сплошь и рядом: между ними бывают
        // минуты. Раньше побеждал пуш, и апрув терялся насовсем — следующий проход
        // разницы уже не видел.
        given(pr(41, "OPEN", 1, "commit-a", List.of(reviewer("ivan", "UNAPPROVED", false))));
        poller.runOnce();

        given(pr(41, "OPEN", 2, "commit-b",
                List.of(reviewer("ivan", "APPROVED", true, "commit-b"))));
        poller.runOnce();

        assertThat(eventTypes()).containsExactly("pr:opened", "pr:reviewer:approved");
    }

    @Test
    void aClosedPullRequestStopsRaisingReviewEvents() {
        // Карточка смерженного PR лежит в «Влито в ветку», и вытащить её оттуда обратно
        // в ревью не должно ничто — в том числе смена формата самой выжимки.
        given(pr(43, "OPEN", 1, "commit-a", List.of(reviewer("ivan", "UNAPPROVED", false))));
        poller.runOnce();
        given(pr(43, "MERGED", 2, "commit-a", List.of(reviewer("ivan", "UNAPPROVED", false))));
        poller.runOnce();

        given(pr(43, "MERGED", 3, "commit-b",
                List.of(reviewer("ivan", "APPROVED", true, "commit-b"))));

        assertThat(poller.runOnce()).isZero();
        assertThat(eventTypes()).containsExactly("pr:opened", "pr:merged");
    }

    @Test
    void firstSightOfANeedsWorkLeftBehindByAPushIsJustOpened() {
        // Снимки сбросили, а в Bitbucket висит NEEDS_WORK по коду, которого уже нет.
        // Карточке место в ревью: ход за ревьюером.
        given(pr(42, "OPEN", 3, "commit-b",
                List.of(reviewer("ivan", "NEEDS_WORK", false, "commit-a"))));

        poller.runOnce();

        assertThat(eventTypes()).containsExactly("pr:opened");
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
    void aTaskAppearingRefreshesTheCardWithoutMovingIt() {
        // pr:modified не отображён ни на какую колонку: чек-лист обновится, а карточка
        // останется там, куда её поставил статус ревьюера.
        given(pr(30, "OPEN", 1, "commit-a", List.of()));
        poller.runOnce();

        givenTasks(30, task(1, "Заменить List на Set", "OPEN"));
        assertThat(poller.runOnce()).isEqualTo(1);

        assertThat(eventTypes()).containsExactly("pr:opened", "pr:modified");
    }

    @Test
    void closingATaskAlsoRefreshesTheCard() {
        given(pr(31, "OPEN", 1, "commit-a", List.of()));
        givenTasks(31, task(1, "Заменить List на Set", "OPEN"));
        poller.runOnce();

        givenTasks(31, task(1, "Заменить List на Set", "RESOLVED"));
        poller.runOnce();

        assertThat(eventTypes()).containsExactly("pr:opened", "pr:modified");
    }

    @Test
    void anUnchangedTaskListRaisesNothing() {
        given(pr(32, "OPEN", 1, "commit-a", List.of()));
        givenTasks(32, task(1, "Заменить List на Set", "OPEN"));
        poller.runOnce();

        assertThat(poller.runOnce()).isZero();
    }

    @Test
    void anUnreadableTaskListLeavesTheLastKnownOneAlone() {
        // Выдуманное «задач нет» стёрло бы чек-лист с карточки на первой же заминке.
        given(pr(33, "OPEN", 1, "commit-a", List.of()));
        givenTasks(33, task(1, "Заменить List на Set", "OPEN"));
        poller.runOnce();

        when(client.blockerComments(anyString(), anyString(), eq(33L), anyInt()))
                .thenThrow(new IllegalStateException("bitbucket is down"));

        assertThat(poller.runOnce()).isZero();
    }

    @Test
    void tasksAreNotAskedAboutForClosedPullRequests() {
        given(pr(34, "MERGED", 1, "commit-a", List.of()));

        poller.runOnce();

        verify(client, never()).blockerComments(anyString(), anyString(), anyLong(), anyInt());
    }

    @Test
    void aConflictAppearingRefreshesTheCardWithoutMovingIt() {
        // Конфликт возникает от чужого мержа в целевую ветку: в самом пул-реквесте не
        // меняется ничего, поэтому сравнивать приходится именно результат мержа.
        given(pr(50, "OPEN", 1, "commit-a", List.of(), merge("CLEAN", true)));
        poller.runOnce();

        given(pr(50, "OPEN", 1, "commit-a", List.of(), merge("CONFLICTED", true)));
        assertThat(poller.runOnce()).isEqualTo(1);

        assertThat(eventTypes()).containsExactly("pr:opened", "pr:modified");
    }

    @Test
    void aResolvedConflictIsSeenToo() {
        // Оба перехода дают pr:modified, и всё остальное в отпечатке у них совпадает —
        // не входи туда сам конфликт, второе событие отбросилось бы как повтор первого,
        // и значок с карточки уже не снялся бы.
        given(pr(51, "OPEN", 1, "commit-a", List.of(), merge("CLEAN", true)));
        poller.runOnce();
        given(pr(51, "OPEN", 1, "commit-a", List.of(), merge("CONFLICTED", true)));
        poller.runOnce();

        given(pr(51, "OPEN", 1, "commit-a", List.of(), merge("CLEAN", true)));
        assertThat(poller.runOnce()).isEqualTo(1);

        assertThat(eventTypes()).containsExactly("pr:opened", "pr:modified", "pr:modified");
    }

    @Test
    void aMergeResultComputedForOldHeadsIsCheckedWithBitbucket() {
        // Bitbucket считает мерж лениво: пока пул-реквест никто не открывал, в списке
        // лежит ответ о старом коде. Верить ему нельзя — надо переспросить.
        given(pr(52, "OPEN", 1, "commit-a", List.of(), merge("CLEAN", false)));
        when(client.mergeStatus(eq("LIZA"), eq("liza"), eq(52L)))
                .thenReturn(new BitbucketClient.MergeStatus(true));

        poller.runOnce();

        assertThat(eventTypes()).containsExactly("pr:opened");
        assertThat(payloads().get(0)).contains("CONFLICTED");
    }

    @Test
    void aFreshMergeResultIsTakenFromTheListWithoutAskingAgain() {
        given(pr(53, "OPEN", 1, "commit-a", List.of(), merge("CONFLICTED", true)));

        poller.runOnce();

        verify(client, never()).mergeStatus(anyString(), anyString(), anyLong());
        assertThat(payloads().get(0)).contains("CONFLICTED");
    }

    @Test
    void anUnreadableMergeStatusLeavesTheLastKnownAnswerAlone() {
        // Выдуманное «конфликтов нет» сняло бы предупреждение с карточки на первой же
        // заминке Bitbucket.
        given(pr(54, "OPEN", 1, "commit-a", List.of(), merge("CONFLICTED", true)));
        poller.runOnce();

        given(pr(54, "OPEN", 1, "commit-a", List.of(), merge("CLEAN", false)));
        when(client.mergeStatus(anyString(), anyString(), eq(54L)))
                .thenThrow(new IllegalStateException("bitbucket is down"));

        assertThat(poller.runOnce()).isZero();
    }

    @Test
    void mergingIsNotAskedAboutForClosedPullRequests() {
        given(pr(55, "MERGED", 1, "commit-a", List.of()));

        poller.runOnce();

        verify(client, never()).mergeStatus(anyString(), anyString(), anyLong());
    }

    @Test
    void aMergedPullRequestStopsBeingConflicted() {
        // Значок должен уйти вместе с самим вопросом «можно ли влить».
        given(pr(56, "OPEN", 1, "commit-a", List.of(), merge("CONFLICTED", true)));
        poller.runOnce();

        given(pr(56, "MERGED", 2, "commit-a", List.of(), merge("CONFLICTED", true)));
        poller.runOnce();

        assertThat(eventTypes()).containsExactly("pr:opened", "pr:merged");
        assertThat(payloads().get(1)).doesNotContain("CONFLICTED");
    }

    @Test
    void aPullRequestThatVanishedFromBitbucketIsSeenAsADeletion() {
        // Единственное событие, которое сравнением снимков не получить: сравнивать не с
        // чем. Без него карточка удалённого PR остаётся на доске, а заведённый заново из
        // той же ветки PR получает вторую — так доска и двоится.
        given(pr(70, "OPEN", 1, "commit-a", List.of()));
        poller.runOnce();

        vanished(70);

        assertThat(poller.runOnce()).isEqualTo(1);
        assertThat(eventTypes()).containsExactly("pr:opened", "pr:deleted");
    }

    @Test
    void theDeletionPayloadCarriesEnoughToFindTheCard() {
        given(pr(71, "OPEN", 1, "commit-a", List.of()));
        poller.runOnce();

        vanished(71);
        poller.runOnce();

        assertThat(payloads().get(1)).contains("LIZA").contains("liza").contains("71");
    }

    @Test
    void aDeletionIsReportedOnceAndBitbucketIsNotAskedAgain() {
        given(pr(72, "OPEN", 1, "commit-a", List.of()));
        poller.runOnce();
        vanished(72);
        poller.runOnce();

        assertThat(poller.runOnce()).isZero();

        assertThat(eventTypes()).containsExactly("pr:opened", "pr:deleted");
        // Второй вопрос о покойнике — это запрос в Bitbucket каждые две минуты навсегда.
        verify(client, times(1)).pullRequest(anyString(), anyString(), eq(72L));
    }

    @Test
    void aPullRequestMerelyOffThePageIsLeftAlone() {
        // Страница ограничена max-results, и старый PR уходит с неё просто от возраста.
        // Принять это за удаление — значит убрать с доски карточку живого PR.
        given(pr(73, "OPEN", 1, "commit-a", List.of()));
        poller.runOnce();

        given();
        when(client.pullRequest(eq("LIZA"), eq("liza"), eq(73L)))
                .thenReturn(pr(73, "OPEN", 1, "commit-a", List.of()));

        assertThat(poller.runOnce()).isZero();
        assertThat(eventTypes()).containsExactly("pr:opened");
    }

    @Test
    void anUnansweredExistenceCheckArchivesNothing() {
        // «Не знаем» — не «удалён». Заминка Bitbucket не должна стоить карточки.
        given(pr(74, "OPEN", 1, "commit-a", List.of()));
        poller.runOnce();

        given();
        when(client.pullRequest(eq("LIZA"), eq("liza"), eq(74L)))
                .thenThrow(new IllegalStateException("bitbucket is down"));

        assertThat(poller.runOnce()).isZero();
        assertThat(eventTypes()).containsExactly("pr:opened");
    }

    @Test
    void aClosedPullRequestLeavingThePageIsNotEvenAskedAbout() {
        // Карточка влитого PR уже лежит в конечной колонке, а закрытые уходят со
        // страницы пачками: спрашивать о каждом — это лишний запрос на каждый проход.
        given(pr(75, "OPEN", 1, "commit-a", List.of()));
        poller.runOnce();
        given(pr(75, "MERGED", 2, "commit-a", List.of()));
        poller.runOnce();

        given();

        assertThat(poller.runOnce()).isZero();
        verify(client, never()).pullRequest(anyString(), anyString(), eq(75L));
    }

    @Test
    void anEmptyAnswerIsNotTakenForAnEmptyRepository() {
        // Ответ без списка — это «нам не ответили», а не «пул-реквестов нет». Иначе один
        // невнятный ответ Bitbucket отправил бы в архив всю доску разом.
        given(pr(76, "OPEN", 1, "commit-a", List.of()));
        poller.runOnce();

        when(client.pullRequests(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new BitbucketClient.PullRequestPage(null));

        assertThat(poller.runOnce()).isZero();
        verify(client, never()).pullRequest(anyString(), anyString(), anyLong());
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

    /** Пул-реквест удалили: со страницы он пропал, а на прямой вопрос Bitbucket отвечает 404. */
    private void vanished(long prId) {
        given();
        when(client.pullRequest(eq("LIZA"), eq("liza"), eq(prId)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatusCode.valueOf(404), "Not Found", null, null, null));
    }

    private void givenTasks(long prId, BitbucketClient.BlockerComment... tasks) {
        when(client.blockerComments(eq("LIZA"), eq("liza"), eq(prId), anyInt()))
                .thenReturn(new BitbucketClient.BlockerComments(List.of(tasks)));
    }

    private static BitbucketClient.BlockerComment task(long id, String text, String state) {
        return new BitbucketClient.BlockerComment(id, text, state);
    }

    private List<String> eventTypes() {
        return jdbc.queryForList("SELECT event_type FROM inbound_event ORDER BY id", String.class);
    }

    private List<String> payloads() {
        return jdbc.queryForList("SELECT payload::text FROM inbound_event ORDER BY id", String.class);
    }

    /** Ревьюер, о чьём последнем просмотренном коммите Bitbucket умолчал. */
    private BitbucketPrEvent.Reviewer reviewer(String name, String status, boolean approved) {
        return reviewer(name, status, approved, null);
    }

    private BitbucketPrEvent.Reviewer reviewer(String name, String status, boolean approved,
                                               String lastReviewedCommit) {
        return new BitbucketPrEvent.Reviewer(
                new BitbucketPrEvent.User(name, name), status, approved, lastReviewedCommit);
    }

    private BitbucketPrEvent.PullRequest pr(long id, String state, int version,
                                            String latestCommit,
                                            List<BitbucketPrEvent.Reviewer> reviewers) {
        return pr(id, state, version, latestCommit, reviewers, null);
    }

    private BitbucketPrEvent.PullRequest pr(long id, String state, int version,
                                            String latestCommit,
                                            List<BitbucketPrEvent.Reviewer> reviewers,
                                            BitbucketPrEvent.Properties properties) {
        BitbucketPrEvent.Repository repository = new BitbucketPrEvent.Repository(
                "liza", "liza", new BitbucketPrEvent.Project("LIZA", "LIZA"));
        return new BitbucketPrEvent.PullRequest(
                id, "Fix the thing", "description", state, version, null, null,
                new BitbucketPrEvent.Ref("refs/heads/feature", "feature", latestCommit, repository),
                new BitbucketPrEvent.Ref("refs/heads/main", "main", "commit-main", repository),
                new BitbucketPrEvent.Author(new BitbucketPrEvent.User("kras", "Grigory")),
                reviewers,
                null,
                properties);
    }

    /** Результат пробного мержа в том виде, в каком его кладёт в список сам Bitbucket. */
    private static BitbucketPrEvent.Properties merge(String outcome, Boolean current) {
        return new BitbucketPrEvent.Properties(new BitbucketPrEvent.MergeResult(outcome, current));
    }
}
