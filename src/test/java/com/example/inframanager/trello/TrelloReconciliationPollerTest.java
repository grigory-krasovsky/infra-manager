package com.example.inframanager.trello;

import java.util.List;

import com.example.inframanager.TestcontainersConfiguration;
import com.example.inframanager.outbound.OutboundTaskRepository;
import com.example.inframanager.pullrequest.PrCardLink;
import com.example.inframanager.pullrequest.PrCardLinkRepository;
import com.example.inframanager.pullrequest.PullRequestRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Drift detection against a mocked Trello. The behaviour under test is what happens
 * to our own state, so the HTTP layer is not the interesting part here.
 */
@SpringBootTest(properties = {
        "infra-manager.workers.scheduling-enabled=false",
        "infra-manager.trello.enabled=true",
        "infra-manager.trello.key=test-key",
        "infra-manager.trello.token=test-token",
        "infra-manager.trello.reconciliation.enabled=true",
        "infra-manager.trello.reconciliation.on-drift=restore",
        "infra-manager.trello.reconciliation.on-missing=forget"
})
@Import(TestcontainersConfiguration.class)
class TrelloReconciliationPollerTest {

    @Autowired
    private TrelloReconciliationPoller poller;
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
                        new TrelloClient.TrelloList("list-merged", "Merged", false)));
    }

    @Test
    void aCardStillWhereWeLeftItIsNotADiscrepancy() {
        givenLink(1, "card-1", "list-review");
        when(trelloClient.card(eq("card-1"), anyString(), anyString()))
                .thenReturn(new TrelloClient.TrelloCard("card-1", "PR", "list-review", false));

        assertThat(poller.runOnce()).isZero();
        assertThat(outboundTasks.count()).isZero();
    }

    @Test
    void aCardDraggedToAnotherColumnIsQueuedToMoveBack() {
        givenLink(2, "card-2", "list-review");
        when(trelloClient.card(eq("card-2"), anyString(), anyString()))
                .thenReturn(new TrelloClient.TrelloCard("card-2", "PR", "list-merged", false));

        assertThat(poller.runOnce()).isEqualTo(1);

        // Postgres re-renders jsonb with its own spacing, so match on values.
        String payload = jdbc.queryForObject(
                "SELECT payload->>'moveToListName' FROM outbound_task LIMIT 1", String.class);
        // The board's own capitalisation, not the lower-cased lookup key.
        assertThat(payload).isEqualTo("Review");

        // Content is left alone: this is a move, not a rewrite.
        assertThat(jdbc.queryForObject("SELECT payload->>'title' FROM outbound_task LIMIT 1", String.class))
                .isNull();
    }

    @Test
    void aDeletedCardIsForgottenSoTheNextEventCreatesAFreshOne() {
        givenLink(3, "card-3", "list-review");
        when(trelloClient.card(eq("card-3"), anyString(), anyString()))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatusCode.valueOf(404), "Not Found", null, null, null));

        assertThat(poller.runOnce()).isEqualTo(1);

        assertThat(links.findByProjectKeyAndRepoSlugAndPrId("INFRA", "backend", 3))
                .hasValueSatisfying(link -> assertThat(link.getTrelloCardId()).isNull());
    }

    @Test
    void oneUnreadableCardDoesNotStopTheSweep() {
        givenLink(4, "card-4", "list-review");
        givenLink(5, "card-5", "list-review");
        when(trelloClient.card(eq("card-4"), anyString(), anyString()))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR, "boom", null, null, null));
        when(trelloClient.card(eq("card-5"), anyString(), anyString()))
                .thenReturn(new TrelloClient.TrelloCard("card-5", "PR", "list-merged", false));

        assertThat(poller.runOnce()).isEqualTo(1);
    }

    @Test
    void linksWithoutACardYetAreNotChecked() {
        transactionTemplate.executeWithoutResult(status ->
                links.save(new PrCardLink(new PullRequestRef("INFRA", "backend", 6), "board-1")));

        assertThat(poller.runOnce()).isZero();
    }

    private void givenLink(long prId, String cardId, String listId) {
        transactionTemplate.executeWithoutResult(status -> {
            PrCardLink link = new PrCardLink(new PullRequestRef("INFRA", "backend", prId), "board-1");
            link.recordCard(cardId, listId, false);
            links.save(link);
        });
    }
}
