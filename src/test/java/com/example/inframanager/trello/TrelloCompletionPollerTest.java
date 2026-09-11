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
 * Взросление карточки до «выполнено»: неделя в колонке влитых — и отметка.
 *
 * <p>Возраст карточки — состояние, которого нет ни в одном событии, поэтому проверяется
 * он на нашей собственной записи, а Trello подменён.
 */
@SpringBootTest(properties = {
        "infra-manager.workers.scheduling-enabled=false",
        "infra-manager.trello.enabled=true",
        "infra-manager.trello.key=test-key",
        "infra-manager.trello.token=test-token",
        "infra-manager.trello.completion.enabled=true",
        "infra-manager.trello.completion.after=7d",
        // Объявлено здесь, а не унаследовано из application.yaml: колонка влитых — это
        // то, что оказалось на конкретной доске, и её переименование не должно ломать тест.
        "infra-manager.lifecycle.event-to-list[0].event=pr:merged",
        "infra-manager.lifecycle.event-to-list[0].list=Merged"
})
@Import(TestcontainersConfiguration.class)
class TrelloCompletionPollerTest {

    @Autowired
    private TrelloCompletionPoller poller;
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
                        new TrelloClient.TrelloList("list-merged", "Merged", false)));
    }

    @Test
    void aCardThatHasSatAmongTheMergedForAWeekIsQueuedAsComplete() {
        givenLink(1, "card-1", "list-merged", 8);

        assertThat(poller.runOnce()).isEqualTo(1);

        // Postgres перерисовывает jsonb со своими пробелами, поэтому сверяемся по значениям.
        assertThat(jdbc.queryForObject(
                "SELECT payload->>'completeAsOf' FROM outbound_task LIMIT 1", String.class)).isNotNull();
        // Карточка остаётся на месте, и содержимое её не переписывается.
        assertThat(jdbc.queryForObject(
                "SELECT payload->>'moveToListName' FROM outbound_task LIMIT 1", String.class)).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT payload->>'title' FROM outbound_task LIMIT 1", String.class)).isNull();
    }

    @Test
    void aCardMergedYesterdayIsLeftAlone() {
        givenLink(2, "card-2", "list-merged", 1);

        assertThat(poller.runOnce()).isZero();
        assertThat(outboundTasks.count()).isZero();
    }

    @Test
    void aCardStillUnderReviewNeverCompletesNoMatterHowLongItSitsThere() {
        givenLink(3, "card-3", "list-review", 30);

        assertThat(poller.runOnce()).isZero();
        assertThat(outboundTasks.count()).isZero();
    }

    @Test
    void theSameCardIsNotQueuedTwice() {
        givenLink(4, "card-4", "list-merged", 8);

        assertThat(poller.runOnce()).isEqualTo(1);
        // Задача ещё не выполнена, отметки на связке нет — и всё равно второй такой же
        // задачи не появляется.
        assertThat(poller.runOnce()).isZero();
        assertThat(outboundTasks.count()).isEqualTo(1);
    }

    @Test
    void theMarkReachesTrelloWithADueDateAndIsRememberedOnTheLink() {
        givenLink(5, "card-5", "list-merged", 8);
        poller.runOnce();

        outboundWorker.runOnce();

        ArgumentCaptor<TrelloClient.UpdateCardRequest> request =
                ArgumentCaptor.forClass(TrelloClient.UpdateCardRequest.class);
        verify(trelloClient).updateCard(eq("card-5"), anyString(), anyString(), request.capture());
        assertThat(request.getValue().dueComplete()).isTrue();
        // Срок обязателен: без него отметку Trello нигде не показывает. Секунды — предел
        // осмысленной точности, доли секунды в него не попадают.
        assertThat(request.getValue().due()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
        // Перемещения нет — только статус.
        assertThat(request.getValue().idList()).isNull();

        assertThat(links.findByProjectKeyAndRepoSlugAndPrId("INFRA", "backend", 5))
                .hasValueSatisfying(link -> assertThat(link.getCompletedAt()).isNotNull());
    }

    @Test
    void anAlreadyCompletedCardIsNotMarkedAgain() {
        givenLink(6, "card-6", "list-merged", 8);
        jdbc.update("UPDATE pr_card_link SET completed_at = now() WHERE pr_id = 6");

        assertThat(poller.runOnce()).isZero();
        assertThat(outboundTasks.count()).isZero();
    }

    @Test
    void aCardThatLeftTheColumnStartsCountingAgain() {
        givenLink(7, "card-7", "list-merged", 8);
        transactionTemplate.executeWithoutResult(status -> {
            PrCardLink link = links.findByProjectKeyAndRepoSlugAndPrId("INFRA", "backend", 7).orElseThrow();
            // Новые коммиты вернули PR на ревью.
            link.recordCard("card-7", "list-review", false);
        });

        assertThat(poller.runOnce()).isZero();
    }

    private void givenLink(long prId, String cardId, String listId, int daysInList) {
        transactionTemplate.executeWithoutResult(status -> {
            PrCardLink link = new PrCardLink(new PullRequestRef("INFRA", "backend", prId), "board-1");
            link.recordCard(cardId, listId, false);
            links.save(link);
        });
        // Карточка попадает в колонку «сейчас», а нужен возраст — его проставляем мимо сущности.
        jdbc.update("UPDATE pr_card_link SET list_entered_at = now() - make_interval(days => ?) WHERE pr_id = ?",
                daysInList, prId);
    }
}
