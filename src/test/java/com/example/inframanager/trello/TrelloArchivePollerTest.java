package com.example.inframanager.trello;

import java.util.List;

import com.example.inframanager.TestcontainersConfiguration;
import com.example.inframanager.outbound.OutboundTaskRepository;
import com.example.inframanager.outbound.OutboundTaskWorker;
import com.example.inframanager.pullrequest.PrCardLink;
import com.example.inframanager.pullrequest.PrCardLinkRepository;
import com.example.inframanager.pullrequest.PullRequestRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Взросление карточки до архива: неделя с закрытия пул-реквеста — и она уходит с доски.
 *
 * <p>Возраст — состояние, которого нет ни в одном отдельном событии, поэтому проверяется
 * он на нашей собственной записи, а Trello подменён.
 */
@SpringBootTest(properties = {
        "infra-manager.workers.scheduling-enabled=false",
        "infra-manager.trello.enabled=true",
        "infra-manager.trello.key=test-key",
        "infra-manager.trello.token=test-token",
        "infra-manager.trello.archive.enabled=true",
        "infra-manager.trello.archive.after=7d",
        // Объявлено здесь, а не унаследовано из application.yaml: конечные колонки — это
        // то, что оказалось на конкретной доске, и их переименование не должно ломать тест.
        "infra-manager.lifecycle.event-to-list[0].event=pr:merged",
        "infra-manager.lifecycle.event-to-list[0].list=Merged",
        "infra-manager.lifecycle.event-to-list[1].event=pr:declined",
        "infra-manager.lifecycle.event-to-list[1].list=Declined"
})
@Import(TestcontainersConfiguration.class)
class TrelloArchivePollerTest {

    @Autowired
    private TrelloArchivePoller poller;
    @Autowired
    private OutboundTaskWorker outboundWorker;
    @Autowired
    private PrCardLinkRepository links;
    @Autowired
    private OutboundTaskRepository outboundTasks;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private TrelloClient trelloClient;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE outbound_task, pr_card_link RESTART IDENTITY");
        when(trelloClient.boardLists(eq("board-1"), anyString(), anyString(), anyString()))
                .thenReturn(List.of(
                        new TrelloClient.TrelloList("list-review", "Review", false),
                        new TrelloClient.TrelloList("list-merged", "Merged", false),
                        new TrelloClient.TrelloList("list-declined", "Declined", false)));
    }

    @Test
    void aPullRequestMergedAWeekAgoIsQueuedForTheArchive() {
        givenLink(1, "card-1", "list-merged", 8);

        assertThat(poller.runOnce()).isEqualTo(1);

        // Postgres перерисовывает jsonb со своими пробелами, поэтому сверяемся по значениям.
        assertThat(jdbc.queryForObject(
                "SELECT payload->>'archive' FROM outbound_task LIMIT 1", String.class)).isEqualTo("true");
        // Карточка уезжает с доски из той колонки, где лежит, и в последнем известном виде.
        assertThat(jdbc.queryForObject(
                "SELECT payload->>'moveToListName' FROM outbound_task LIMIT 1", String.class)).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT payload->>'title' FROM outbound_task LIMIT 1", String.class)).isNull();
    }

    @Test
    void aDeclinedPullRequestIsArchivedJustTheSame() {
        givenLink(2, "card-2", "list-declined", 8);

        assertThat(poller.runOnce()).isEqualTo(1);
    }

    @Test
    void aPullRequestMergedYesterdayIsLeftAlone() {
        givenLink(3, "card-3", "list-merged", 1);

        assertThat(poller.runOnce()).isZero();
        assertThat(outboundTasks.count()).isZero();
    }

    @Test
    void aCardDraggedOutOfItsFinalColumnIsLeftAlone() {
        givenLink(8, "card-8", "list-review", 30);

        assertThat(poller.runOnce()).isZero();
        assertThat(outboundTasks.count()).isZero();
    }

    @Test
    void theSameCardIsNotQueuedTwice() {
        givenLink(4, "card-4", "list-merged", 8);

        assertThat(poller.runOnce()).isEqualTo(1);
        // Задача ещё не выполнена, связка ещё не помечена архивной — и всё равно второй
        // такой же задачи не появляется.
        assertThat(poller.runOnce()).isZero();
        assertThat(outboundTasks.count()).isEqualTo(1);
    }

    @Test
    void theArchivalReachesTrelloAndIsRememberedOnTheLink() {
        givenLink(5, "card-5", "list-merged", 8);
        poller.runOnce();

        outboundWorker.runOnce();

        ArgumentCaptor<TrelloClient.UpdateCardRequest> request =
                ArgumentCaptor.forClass(TrelloClient.UpdateCardRequest.class);
        verify(trelloClient).updateCard(eq("card-5"), anyString(), anyString(), request.capture());
        assertThat(request.getValue().closed()).isTrue();
        // Перемещения нет: карточка уходит с доски из той колонки, в которой лежит.
        // Дату начала уносит с собой — в архиве видно, на чём она остановилась.
        assertThat(request.getValue().idList()).isNull();
        assertThat(request.getValue().start())
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");

        assertThat(links.findByProjectKeyAndRepoSlugAndPrId("INFRA", "backend", 5))
                .hasValueSatisfying(link -> assertThat(link.isArchived()).isTrue());
    }

    @Test
    void anAlreadyArchivedCardIsNotQueuedAgain() {
        givenLink(6, "card-6", "list-merged", 8);
        jdbc.update("UPDATE pr_card_link SET archived = true WHERE pr_id = 6");

        assertThat(poller.runOnce()).isZero();
        assertThat(outboundTasks.count()).isZero();
    }

    @Test
    void aPullRequestStillOpenHasNothingToCountFrom() {
        transactionTemplate.executeWithoutResult(status -> {
            PrCardLink link = new PrCardLink(new PullRequestRef("INFRA", "backend", 7), "board-1");
            // Карточка в колонке ревью, момента закрытия нет — считать не от чего.
            link.recordCard("card-7", "list-review", false);
            links.save(link);
        });

        assertThat(poller.runOnce()).isZero();
    }

    private void givenLink(long prId, String cardId, String listId, int daysSinceClosed) {
        transactionTemplate.executeWithoutResult(status -> {
            PrCardLink link = new PrCardLink(new PullRequestRef("INFRA", "backend", prId), "board-1");
            link.recordCard(cardId, listId, false);
            links.save(link);
        });
        // Момент закрытия приходит из Bitbucket, а здесь его надо просто назначить.
        // Тогда же карточка попала в конечную колонку — этим же моментом она и датирована.
        jdbc.update("UPDATE pr_card_link SET closed_at = now() - make_interval(days => ?), "
                        + "list_entered_at = now() - make_interval(days => ?) WHERE pr_id = ?",
                daysSinceClosed, daysSinceClosed, prId);
    }
}
