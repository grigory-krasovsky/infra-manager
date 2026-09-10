package com.example.inframanager.deployment;

import java.time.Duration;

import com.example.inframanager.TestcontainersConfiguration;
import com.example.inframanager.event.InboundEventRepository;
import com.example.inframanager.event.InboundEventWorker;
import com.example.inframanager.outbound.OutboundTaskRepository;
import com.example.inframanager.work.ProcessingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The phase 3 acceptance path end to end: Bamboo posts a deployment result, and a
 * Telegram message ends up queued for the right chat with the right text.
 */
@SpringBootTest(properties = {
        "infra-manager.workers.scheduling-enabled=false",
        "infra-manager.bamboo.source=webhook",
        "infra-manager.bamboo.webhook-secret=test-secret",
        "infra-manager.bamboo.project-names.777=INFRA-BY-ID",
        "infra-manager.telegram.routes[0].chat-id=-100ops"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BambooDeploymentFlowTest {

    private static final String URL = "/webhooks/bamboo";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private InboundEventWorker worker;
    @Autowired
    private InboundEventRepository inboundEvents;
    @Autowired
    private OutboundTaskRepository outboundTasks;
    @Autowired
    private DeploymentRecordRepository deployments;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE inbound_event, outbound_task, deployment_record RESTART IDENTITY");
    }

    @Test
    void deploymentBecomesAQueuedTelegramMessage() throws Exception {
        postWebhook(body(1001, "SUCCESS", "INFRA", "STAGE"));

        worker.runOnce();

        assertThat(deployments.findByBambooDeploymentResultId(1001)).hasValueSatisfying(record -> {
            assertThat(record.getEnvironmentName()).isEqualTo("STAGE");
            assertThat(record.getStatus()).isEqualTo("SUCCESS");
            assertThat(record.getNotifiedAt()).isNotNull();
        });

        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM outbound_task WHERE dedup_key = ?",
                String.class, "telegram:deploy:1001:-100ops");
        assertThat(payload).contains("INFRA").contains("STAGE").contains("задеплоен");
    }

    @Test
    void redeliveryOfTheSameResultDoesNotAnnounceTwice() throws Exception {
        postWebhook(body(1002, "SUCCESS", "INFRA", "STAGE"));
        postWebhook(body(1002, "SUCCESS", "INFRA", "STAGE"));

        worker.runOnce();

        assertThat(inboundEvents.count()).isEqualTo(1);
        assertThat(outboundTasks.count()).isEqualTo(1);
    }

    @Test
    void aQueuedObservationIsRecordedAndOnlyTheFinishedOneIsAnnounced() throws Exception {
        // Distinct external ids, because status is part of the key -- so both reach
        // the handler, but only the terminal one produces a message.
        postWebhook("""
                {"deploymentResultId":1003,"status":"UNKNOWN","lifeCycleState":"IN_PROGRESS",
                 "deploymentProjectName":"INFRA","environmentName":"STAGE"}
                """);
        worker.runOnce();
        assertThat(outboundTasks.count()).isZero();
        assertThat(deployments.findByBambooDeploymentResultId(1003)).isPresent();

        postWebhook(body(1003, "SUCCESS", "INFRA", "STAGE"));
        worker.runOnce();

        assertThat(outboundTasks.count()).isEqualTo(1);
        assertThat(deployments.count()).isEqualTo(1);
    }

    @Test
    void projectNameCanComeFromConfigurationWhenThePayloadHasOnlyAnId() throws Exception {
        postWebhook("""
                {"deploymentResultId":1004,"status":"SUCCESS","deploymentProjectId":777,
                 "environmentName":"PROD"}
                """);

        worker.runOnce();

        assertThat(deployments.findByBambooDeploymentResultId(1004))
                .hasValueSatisfying(record -> assertThat(record.getProjectName()).isEqualTo("INFRA-BY-ID"));
    }

    @Test
    void wrongSecretIsRejectedAndRecordsNothing() throws Exception {
        mockMvc.perform(post(URL)
                        .header("X-Infra-Manager-Secret", "not-the-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1005, "SUCCESS", "INFRA", "STAGE")))
                .andExpect(status().isUnauthorized());

        assertThat(inboundEvents.count()).isZero();
    }

    @Test
    void missingSecretIsRejected() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1006, "SUCCESS", "INFRA", "STAGE")))
                .andExpect(status().isUnauthorized());

        assertThat(inboundEvents.count()).isZero();
    }

    @Test
    void secretMayAlsoArriveAsAQueryParameter() throws Exception {
        mockMvc.perform(post(URL + "?secret=test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1007, "SUCCESS", "INFRA", "STAGE")))
                .andExpect(status().isOk());

        assertThat(inboundEvents.count()).isEqualTo(1);
    }

    @Test
    void payloadWithoutAResultIdIsRejectedUpFront() throws Exception {
        mockMvc.perform(post(URL)
                        .header("X-Infra-Manager-Secret", "test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUCCESS\",\"environmentName\":\"STAGE\"}"))
                .andExpect(status().isBadRequest());

        assertThat(inboundEvents.count()).isZero();
    }

    @Test
    void failedDeploymentIsAnnouncedToo() throws Exception {
        postWebhook(body(1008, "FAILED", "INFRA", "PROD"));

        worker.runOnce();

        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM outbound_task WHERE dedup_key = ?",
                String.class, "telegram:deploy:1008:-100ops");
        assertThat(payload).contains("не прошёл");
    }

    @Test
    void aDeploymentThatFinishedLongAgoIsRecordedButNotAnnounced() throws Exception {
        // What the first poll against a live Bamboo sees: a backlog of historical
        // results. Recording them is right; announcing them would flood the chat.
        long finished = System.currentTimeMillis() - Duration.ofDays(3).toMillis();
        postWebhook(body(1009, "SUCCESS", "INFRA", "STAGE", finished - 30_000, finished));

        worker.runOnce();

        assertThat(outboundTasks.count()).isZero();
        assertThat(deployments.findByBambooDeploymentResultId(1009)).hasValueSatisfying(record -> {
            assertThat(record.getStatus()).isEqualTo("SUCCESS");
            // Marked notified so a later observation cannot resurrect it.
            assertThat(record.getNotifiedAt()).isNotNull();
        });
    }

    @Test
    void aDeploymentWithNoFinishTimeIsStillAnnounced() throws Exception {
        // Only happens on webhook payloads, which arrive as the deployment ends.
        // Staying silent about a real deployment is the worse failure.
        postWebhook("""
                {"deploymentResultId":1010,"status":"SUCCESS","deploymentProjectName":"INFRA",
                 "environmentName":"STAGE"}
                """);

        worker.runOnce();

        assertThat(outboundTasks.count()).isEqualTo(1);
    }

    @Test
    void unparseableBodyIsRejected() throws Exception {
        mockMvc.perform(post(URL)
                        .header("X-Infra-Manager-Secret", "test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not json at all"))
                .andExpect(status().isBadRequest());
    }

    private void postWebhook(String body) throws Exception {
        mockMvc.perform(post(URL)
                        .header("X-Infra-Manager-Secret", "test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    /** Timestamps are relative to now: a fixed past date would age past the announcement window. */
    private String body(long resultId, String status, String project, String environment) {
        long finished = System.currentTimeMillis();
        return body(resultId, status, project, environment, finished - 30_000, finished);
    }

    private String body(long resultId, String status, String project, String environment,
                        long startedAt, long finishedAt) {
        return """
                {"deploymentResultId":%d,"status":"%s","deploymentProjectName":"%s",
                 "environmentName":"%s","deploymentVersionName":"release-1",
                 "startedAt":"%d","finishedAt":"%d",
                 "triggerSentence":"Manual run"}
                """.formatted(resultId, status, project, environment, startedAt, finishedAt);
    }
}
