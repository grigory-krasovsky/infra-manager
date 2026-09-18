package com.example.inframanager.trello;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.example.inframanager.TestcontainersConfiguration;
import com.example.inframanager.outbound.OutboundTaskWorker;
import com.example.inframanager.pullrequest.PrCardLink;
import com.example.inframanager.pullrequest.PrCardLinkRepository;
import com.example.inframanager.pullrequest.PullRequestRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Перерисовка доски: карточке отсылается то, что о ней хранится, и ничего сверх того.
 *
 * <p>Нужна после раскатки, меняющей вид карточки. Без неё увидеть новое можно, только
 * дождавшись событий из Bitbucket, а по закрытому пул-реквесту их не будет никогда.
 */
@SpringBootTest(properties = {
        "infra-manager.workers.scheduling-enabled=false",
        "infra-manager.trello.enabled=true",
        "infra-manager.trello.key=test-key",
        "infra-manager.trello.token=test-token",
        "infra-manager.trello.repaint-on-start=true"
})
@Import(TestcontainersConfiguration.class)
class TrelloCardRepaintTest {

    @Autowired
    private TrelloCardRepaint repaint;
    @Autowired
    private OutboundTaskWorker outboundWorker;
    @Autowired
    private PrCardLinkRepository links;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private TrelloClient trelloClient;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE outbound_task, pr_card_link RESTART IDENTITY");
    }

    @Test
    void aCardIsSentItsStartDateAndNothingElse() {
        Instant entered = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        givenLink(1, "card-1", entered);

        repaint.run(new DefaultApplicationArguments());
        outboundWorker.runOnce();

        ArgumentCaptor<TrelloClient.UpdateCardRequest> request =
                ArgumentCaptor.forClass(TrelloClient.UpdateCardRequest.class);
        verify(trelloClient).updateCard(eq("card-1"), anyString(), anyString(), request.capture());
        assertThat(request.getValue().start()).isEqualTo(entered.toString());
        // Срок снимается: карточка не отмечена выполненной, а непогашенный срок Trello
        // красит красным. Ровно эту красноту перерисовка с доски и убирает.
        assertThat(request.getValue().due()).isEqualTo("");
        // И ничего больше: содержимое выводится из пул-реквеста, а туда проход не ходит.
        assertThat(request.getValue().idList()).isNull();
        assertThat(request.getValue().name()).isNull();
        assertThat(request.getValue().desc()).isNull();
        assertThat(request.getValue().idLabels()).isNull();
    }

    @Test
    void aCardWeNeverCreatedIsLeftAlone() {
        // Связка без карточки — это пул-реквест, который мы ещё не отзеркалили.
        // Заводить ему безымянную карточку перерисовка не должна.
        givenLink(2, null, Instant.now());

        repaint.run(new DefaultApplicationArguments());
        outboundWorker.runOnce();

        verify(trelloClient, never()).createCard(anyString(), anyString(), org.mockito.ArgumentMatchers.any());
        verify(trelloClient, never()).updateCard(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void repaintingTwiceInTheSameMinuteQueuesTheCardOnce() {
        // Перезапуск сервиса — не повод рисовать доску второй раз.
        givenLink(3, "card-3", Instant.now());

        repaint.run(new DefaultApplicationArguments());
        repaint.run(new DefaultApplicationArguments());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbound_task", Integer.class)).isEqualTo(1);
    }

    private void givenLink(long prId, String cardId, Instant enteredList) {
        transactionTemplate.executeWithoutResult(status -> {
            PrCardLink link = new PrCardLink(new PullRequestRef("INFRA", "backend", prId), "board-1");
            if (cardId != null) {
                link.recordCard(cardId, "list-review", false);
            }
            link.enteredList(enteredList);
            links.save(link);
        });
    }
}
