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
 * Приёмочный путь фазы 3 от начала до конца: Bamboo постит результат деплоя, а в очереди
 * оказывается сообщение Telegram — в нужный чат и с нужным текстом.
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
        // Разные внешние id, потому что статус входит в ключ: до обработчика доходят оба,
        // но сообщение порождает только терминальный.
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
        // То, что видит первый опрос живого Bamboo: залежи исторических результатов.
        // Записать их правильно; объявить — значит завалить чат.
        long finished = System.currentTimeMillis() - Duration.ofDays(3).toMillis();
        postWebhook(body(1009, "SUCCESS", "INFRA", "STAGE", finished - 30_000, finished));

        worker.runOnce();

        assertThat(outboundTasks.count()).isZero();
        assertThat(deployments.findByBambooDeploymentResultId(1009)).hasValueSatisfying(record -> {
            assertThat(record.getStatus()).isEqualTo("SUCCESS");
            // Помечен объявленным, чтобы более позднее наблюдение не воскресило его.
            assertThat(record.getNotifiedAt()).isNotNull();
        });
    }

    @Test
    void aDeploymentWithNoFinishTimeIsStillAnnounced() throws Exception {
        // Бывает только у payload'ов вебхука, которые приходят в момент окончания деплоя.
        // Промолчать о настоящем деплое — сбой похуже.
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

    /** Метки времени отсчитываются от «сейчас»: фиксированная дата в прошлом выпала бы из окна объявления. */
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
