package com.example.inframanager.pullrequest;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.example.inframanager.TestcontainersConfiguration;
import com.example.inframanager.event.InboundEventRepository;
import com.example.inframanager.event.InboundEventWorker;
import com.example.inframanager.jira.JiraClient;
import com.example.inframanager.outbound.OutboundTaskRepository;
import com.example.inframanager.outbound.OutboundTaskWorker;
import com.example.inframanager.trello.TrelloClient;
import com.example.inframanager.work.ProcessingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Приёмочный путь фазы 4 от начала до конца: Bitbucket постит событие пул-реквеста,
 * карточка Trello создаётся, затем перемещается, а связь между ними в промежутке
 * помнится.
 *
 * <p>Сам Trello подменяется на уровне интерфейса клиента, а не по HTTP: форма HTTP
 * покрыта контрактом самого клиента, а здесь важно, какие операции с карточкой выбирает
 * жизненный цикл.
 */
@SpringBootTest(properties = {
        "infra-manager.workers.scheduling-enabled=false",
        "infra-manager.bitbucket.enabled=true",
        "infra-manager.bitbucket.webhook-secret=hook-secret",
        "infra-manager.bitbucket.browse-url=https://bitbucket.local",
        "infra-manager.trello.enabled=true",
        "infra-manager.trello.key=test-key",
        "infra-manager.trello.token=test-token",
        "infra-manager.jira.enabled=true",
        "infra-manager.jira.base-url=https://jira.local",
        "infra-manager.jira.token=jira-token",
        "infra-manager.lifecycle.repos[0].project-key=INFRA",
        "infra-manager.lifecycle.repos[0].repo-slug=backend",
        "infra-manager.lifecycle.repos[0].trello-board-id=board-1",
        "infra-manager.lifecycle.repos[0].prefix=BACK",
        // Объявлено здесь, а не унаследовано из application.yaml: имена колонок —
        // это то, что оказалось на конкретной доске, и переименование не должно ломать тесты.
        "infra-manager.lifecycle.event-to-list[0].event=pr:opened",
        "infra-manager.lifecycle.event-to-list[0].list=Review",
        "infra-manager.lifecycle.event-to-list[1].event=pr:reviewer:approved",
        "infra-manager.lifecycle.event-to-list[1].list=Approved",
        "infra-manager.lifecycle.event-to-list[2].event=pr:merged",
        "infra-manager.lifecycle.event-to-list[2].list=Merged",
        "infra-manager.lifecycle.event-to-list[3].event=pr:declined",
        "infra-manager.lifecycle.event-to-list[3].list=Declined",
        "infra-manager.lifecycle.branch-labels[0].branch=postgres",
        "infra-manager.lifecycle.branch-labels[0].label=test",
        "infra-manager.lifecycle.default-list=Review"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BitbucketPrFlowTest {

    private static final String URL = "/webhooks/bitbucket";
    private static final String SECRET = "hook-secret";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private InboundEventWorker inboundWorker;
    @Autowired
    private OutboundTaskWorker outboundWorker;
    @Autowired
    private InboundEventRepository inboundEvents;
    @Autowired
    private OutboundTaskRepository outboundTasks;
    @Autowired
    private PrCardLinkRepository links;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private TrelloClient trelloClient;

    @MockitoBean
    private JiraClient jiraClient;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE inbound_event, outbound_task, pr_card_link RESTART IDENTITY");
        when(trelloClient.boardLists(eq("board-1"), anyString(), anyString(), anyString()))
                .thenReturn(List.of(
                        new TrelloClient.TrelloList("list-review", "Review", false),
                        new TrelloClient.TrelloList("list-approved", "Approved", false),
                        new TrelloClient.TrelloList("list-merged", "Merged", false)));
        when(trelloClient.boardLabels(eq("board-1"), anyString(), anyString(), anyInt()))
                .thenReturn(List.of());
        // Метки заводятся по мере надобности; id повторяет имя, чтобы в проверках было
        // видно, что именно повесили на карточку.
        when(trelloClient.createLabel(anyString(), anyString(), eq("board-1"), anyString(), anyString()))
                .thenAnswer(invocation -> new TrelloClient.TrelloLabel(
                        "label-" + invocation.getArgument(3), invocation.getArgument(3),
                        invocation.getArgument(4)));
        when(trelloClient.createCard(anyString(), anyString(), any()))
                .thenReturn(new TrelloClient.TrelloCard("card-1", "PR", "list-review", false));
        when(trelloClient.updateCard(anyString(), anyString(), anyString(), any()))
                .thenReturn(new TrelloClient.TrelloCard("card-1", "PR", "list-merged", false));
    }

    @Test
    void openingAPullRequestCreatesACardInTheReviewList() throws Exception {
        deliver("pr:opened", payload(42, "Fix the thing"));

        drain();

        ArgumentCaptor<TrelloClient.CreateCardRequest> request =
                ArgumentCaptor.forClass(TrelloClient.CreateCardRequest.class);
        verify(trelloClient).createCard(eq("test-key"), eq("test-token"), request.capture());
        assertThat(request.getValue().idList()).isEqualTo("list-review");
        assertThat(request.getValue().name()).isEqualTo("BACK · Fix the thing");
        assertThat(request.getValue().desc())
                .contains("https://bitbucket.local/projects/INFRA/repos/backend/pull-requests/42")
                .contains("INFRA/backend")
                .contains("feature/thing → main");
        // Проект — чтобы на общей доске можно было отобрать карточки одного репозитория
        // фильтром; ветка — чтобы было видно, куда поедет изменение.
        assertThat(request.getValue().idLabels()).isEqualTo("label-BACK,label-main");

        assertThat(links.findByProjectKeyAndRepoSlugAndPrId("INFRA", "backend", 42))
                .hasValueSatisfying(link -> {
                    assertThat(link.getTrelloCardId()).isEqualTo("card-1");
                    assertThat(link.getCurrentListId()).isEqualTo("list-review");
                    assertThat(link.isArchived()).isFalse();
                });
    }

    @Test
    void mergingMovesTheExistingCardInsteadOfCreatingASecondOne() throws Exception {
        deliver("pr:opened", payload(43, "Fix the thing"));
        drain();

        deliver("pr:merged", payload(43, "Fix the thing"));
        drain();

        verify(trelloClient).createCard(anyString(), anyString(), any());

        ArgumentCaptor<TrelloClient.UpdateCardRequest> request =
                ArgumentCaptor.forClass(TrelloClient.UpdateCardRequest.class);
        verify(trelloClient).updateCard(eq("card-1"), anyString(), anyString(), request.capture());
        assertThat(request.getValue().idList()).isEqualTo("list-merged");
        assertThat(request.getValue().closed()).isFalse();
    }

    @Test
    void anEventWithNoListMappingRefreshesContentWithoutMovingTheCard() throws Exception {
        deliver("pr:opened", payload(44, "Old title"));
        drain();

        deliver("pr:modified", payload(44, "New title"));
        drain();

        ArgumentCaptor<TrelloClient.UpdateCardRequest> request =
                ArgumentCaptor.forClass(TrelloClient.UpdateCardRequest.class);
        verify(trelloClient).updateCard(eq("card-1"), anyString(), anyString(), request.capture());
        assertThat(request.getValue().idList()).isNull();
        assertThat(request.getValue().name()).isEqualTo("BACK · New title");
    }

    @Test
    void newCommitsSendAnApprovedCardBackForReview() throws Exception {
        deliver("pr:opened", payload(45, "Fix"));
        drain();
        deliver("pr:reviewer:approved", payload(45, "Fix"));
        drain();
        deliver("pr:from_ref_updated", payload(45, "Fix"));
        drain();

        ArgumentCaptor<TrelloClient.UpdateCardRequest> request =
                ArgumentCaptor.forClass(TrelloClient.UpdateCardRequest.class);
        verify(trelloClient, org.mockito.Mockito.times(2))
                .updateCard(eq("card-1"), anyString(), anyString(), request.capture());
        assertThat(request.getAllValues()).extracting(TrelloClient.UpdateCardRequest::idList)
                .containsExactly("list-approved", "list-review");
    }

    @Test
    void deletingAPullRequestArchivesItsCard() throws Exception {
        deliver("pr:opened", payload(46, "Fix"));
        drain();

        deliver("pr:deleted", payload(46, "Fix"));
        drain();

        ArgumentCaptor<TrelloClient.UpdateCardRequest> request =
                ArgumentCaptor.forClass(TrelloClient.UpdateCardRequest.class);
        verify(trelloClient).updateCard(eq("card-1"), anyString(), anyString(), request.capture());
        assertThat(request.getValue().closed()).isTrue();
        assertThat(links.findByProjectKeyAndRepoSlugAndPrId("INFRA", "backend", 46))
                .hasValueSatisfying(link -> assertThat(link.isArchived()).isTrue());
    }

    @Test
    void deletingAPullRequestWeNeverMirroredDoesNothing() throws Exception {
        deliver("pr:deleted", payload(47, "Never seen"));

        drain();

        verify(trelloClient, never()).createCard(anyString(), anyString(), any());
        verify(trelloClient, never()).updateCard(anyString(), anyString(), anyString(), any());
    }

    @Test
    void redeliveryOfTheSameEventDoesNotTouchTrelloTwice() throws Exception {
        String body = payload(48, "Fix");
        deliver("pr:opened", body);
        deliver("pr:opened", body);

        drain();

        assertThat(inboundEvents.count()).isEqualTo(1);
        assertThat(outboundTasks.count()).isEqualTo(1);
        verify(trelloClient).createCard(anyString(), anyString(), any());
    }

    @Test
    void eventsForRepositoriesWeDoNotMirrorAreIgnored() throws Exception {
        deliver("pr:opened", payload(49, "Fix", "OTHER", "frontend"));

        drain();

        assertThat(outboundTasks.count()).isZero();
        assertThat(inboundEvents.findAll())
                .allSatisfy(e -> assertThat(e.getStatus()).isEqualTo(ProcessingStatus.DONE));
    }

    @Test
    void aForgedSignatureIsRejectedAndRecordsNothing() throws Exception {
        mockMvc.perform(post(URL)
                        .header("X-Event-Key", "pr:opened")
                        .header("X-Hub-Signature", "sha256=deadbeef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(50, "Fix")))
                .andExpect(status().isUnauthorized());

        assertThat(inboundEvents.count()).isZero();
    }

    @Test
    void aMissingEventKeyHeaderIsRejected() throws Exception {
        String body = payload(51, "Fix");
        mockMvc.perform(post(URL)
                        .header("X-Hub-Signature", sign(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        assertThat(inboundEvents.count()).isZero();
    }

    @Test
    void aBranchWithAnIssueKeyGetsTheJiraSummaryInTheCardTitle() throws Exception {
        when(jiraClient.issue(eq("PROJ-100"), anyString()))
                .thenReturn(new JiraClient.Issue("PROJ-100",
                        new JiraClient.Issue.Fields("Починить деплой на STAGE")));

        deliver("pr:opened", payloadOnBranch(60, "fix stuff", "feature/PROJ-100-fix"));
        drain();

        ArgumentCaptor<TrelloClient.CreateCardRequest> request =
                ArgumentCaptor.forClass(TrelloClient.CreateCardRequest.class);
        verify(trelloClient).createCard(anyString(), anyString(), request.capture());
        assertThat(request.getValue().name()).isEqualTo("BACK · [PROJ-100] Починить деплой на STAGE");

        assertThat(links.findByProjectKeyAndRepoSlugAndPrId("INFRA", "backend", 60))
                .hasValueSatisfying(link -> assertThat(link.getIssueKey()).isEqualTo("PROJ-100"));
    }

    @Test
    void aBrokenJiraStillProducesACardWithThePullRequestTitle() throws Exception {
        when(jiraClient.issue(eq("PROJ-101"), anyString()))
                .thenThrow(new IllegalStateException("jira is down"));

        deliver("pr:opened", payloadOnBranch(61, "fix stuff", "feature/PROJ-101-fix"));
        drain();

        ArgumentCaptor<TrelloClient.CreateCardRequest> request =
                ArgumentCaptor.forClass(TrelloClient.CreateCardRequest.class);
        verify(trelloClient).createCard(anyString(), anyString(), request.capture());
        // Ключ по-прежнему известен из ветки, не хватает только summary.
        assertThat(request.getValue().name()).isEqualTo("BACK · [PROJ-101] fix stuff");
        assertThat(inboundEvents.findAll())
                .allSatisfy(e -> assertThat(e.getStatus()).isEqualTo(ProcessingStatus.DONE));
    }

    @Test
    void aBranchThatStandsForTestCarriesTheTestLabel() throws Exception {
        deliver("pr:opened", payloadIntoBranch(62, "Fix", "postgres"));
        drain();

        ArgumentCaptor<TrelloClient.CreateCardRequest> request =
                ArgumentCaptor.forClass(TrelloClient.CreateCardRequest.class);
        verify(trelloClient).createCard(anyString(), anyString(), request.capture());
        assertThat(request.getValue().idLabels()).isEqualTo("label-BACK,label-test");
        // Метка — для фильтра, а не подмена факта: куда именно поедет изменение,
        // в описании написано честно.
        assertThat(request.getValue().desc()).contains("feature/thing → postgres");
    }

    private void drain() {
        inboundWorker.runOnce();
        outboundWorker.runOnce();
    }

    private void deliver(String eventKey, String body) throws Exception {
        mockMvc.perform(post(URL)
                        .header("X-Event-Key", eventKey)
                        .header("X-Hub-Signature", sign(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private String payload(long prId, String title) {
        return payload(prId, title, "INFRA", "backend", "feature/thing");
    }

    private String payloadOnBranch(long prId, String title, String branch) {
        return payload(prId, title, "INFRA", "backend", branch);
    }

    private String payload(long prId, String title, String projectKey, String repoSlug) {
        return payload(prId, title, projectKey, repoSlug, "feature/thing");
    }

    private String payload(long prId, String title, String projectKey, String repoSlug, String branch) {
        return payload(prId, title, projectKey, repoSlug, branch, "main");
    }

    private String payloadIntoBranch(long prId, String title, String targetBranch) {
        return payload(prId, title, "INFRA", "backend", "feature/thing", targetBranch);
    }

    private String payload(long prId, String title, String projectKey, String repoSlug,
                           String branch, String targetBranch) {
        return """
                {"eventKey":"pr:event","actor":{"name":"kras","displayName":"Grigory"},
                 "pullRequest":{"id":%d,"title":"%s","state":"OPEN",
                  "fromRef":{"displayId":"%s",
                             "repository":{"slug":"%s","project":{"key":"%s"}}},
                  "toRef":{"displayId":"%s",
                           "repository":{"slug":"%s","project":{"key":"%s"}}},
                  "author":{"user":{"name":"kras","displayName":"Grigory"}},
                  "reviewers":[{"user":{"displayName":"Reviewer One"},"approved":false}]}}
                """.formatted(prId, title, branch, repoSlug, projectKey, targetBranch, repoSlug, projectKey);
    }

    private static String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
