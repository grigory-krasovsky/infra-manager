package com.example.inframanager.deployment;

import java.time.Duration;

import com.example.inframanager.TestcontainersConfiguration;
import com.example.inframanager.event.EventSource;
import com.example.inframanager.event.InboundEventIngestService;
import com.example.inframanager.event.InboundEventWorker;
import com.example.inframanager.outbound.OutboundTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Путь для сборок, упавших раньше релиза: {@link BambooBuildPoller} проверен отдельно
 * (без базы, через {@code MockRestServiceServer}), здесь — то, что происходит дальше,
 * после того как провал уже лёг строкой в {@code inbound_event}.
 */
@SpringBootTest(properties = {
        "infra-manager.workers.scheduling-enabled=false",
        "infra-manager.bamboo.source=webhook",
        "infra-manager.bamboo.webhook-secret=test-secret",
        "infra-manager.telegram.routes[0].chat-id=-100ops"
})
@Import(TestcontainersConfiguration.class)
class BambooBuildFailureFlowTest {

    @Autowired
    private InboundEventIngestService ingestService;
    @Autowired
    private InboundEventWorker worker;
    @Autowired
    private OutboundTaskRepository outboundTasks;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE inbound_event, outbound_task, deployment_record RESTART IDENTITY");
    }

    @Test
    void aFailedBuildBecomesAQueuedTelegramMessage() {
        long finished = System.currentTimeMillis();
        ingestService.ingest(EventSource.BAMBOO, "LIZA-REST-3081", "build", """
                {"buildResultKey":"LIZA-REST-3081","buildState":"Failed",
                 "projectName":"Лиза API","environmentName":"dev",
                 "startedAt":"%d","finishedAt":"%d",
                 "buildReason":"Changes by <a href=\\"https://bamboo.local/u\\">Красовский Григорий</a>",
                 "resultUrl":"https://bamboo.local/browse/LIZA-REST-3081"}
                """.formatted(finished - 46_000, finished));

        worker.runOnce();

        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM outbound_task WHERE dedup_key = ?",
                String.class, "telegram:build:LIZA-REST-3081:-100ops");
        assertThat(payload).contains("Лиза API").contains("dev").contains("не прошла")
                .contains("LIZA-REST-3081").contains("Красовский Григорий");
    }

    @Test
    void aSuccessfulDeploymentIsUnaffectedByTheBuildBranch() {
        // Тот же обработчик обслуживает оба вида событий -- убеждаемся, что ветвление по
        // event_type не задело путь деплоя.
        ingestService.ingest(EventSource.BAMBOO, "9001:SUCCESS", "deployment", """
                {"deploymentResultId":9001,"status":"SUCCESS","deploymentProjectName":"INFRA",
                 "environmentName":"STAGE"}
                """);

        worker.runOnce();

        assertThat(outboundTasks.count()).isEqualTo(1);
    }

    @Test
    void aStaleFailureIsRecordedButNotAnnounced() {
        long finished = System.currentTimeMillis() - Duration.ofDays(3).toMillis();
        ingestService.ingest(EventSource.BAMBOO, "LIZA-REST-3000", "build", """
                {"buildResultKey":"LIZA-REST-3000","buildState":"Failed",
                 "projectName":"Лиза API","environmentName":"dev",
                 "startedAt":"%d","finishedAt":"%d"}
                """.formatted(finished - 30_000, finished));

        worker.runOnce();

        assertThat(outboundTasks.count()).isZero();
    }
}
