package com.example.inframanager.notify;

import java.util.List;

import com.example.inframanager.TestcontainersConfiguration;
import com.example.inframanager.outbound.OutboundTaskRepository;
import com.example.inframanager.outbound.OutboundTaskWorker;
import com.example.inframanager.work.ProcessingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "infra-manager.workers.scheduling-enabled=false",
        // Два маршрута: одному интересен только PROD, другому — все стенды.
        "infra-manager.telegram.routes[0].chat-id=-100prod",
        "infra-manager.telegram.routes[0].environments[0]=PROD",
        "infra-manager.telegram.routes[1].chat-id=-100all"
})
@Import(TestcontainersConfiguration.class)
class TelegramNotifierTest {

    @Autowired
    private TelegramNotifier notifier;
    @Autowired
    private OutboundTaskRepository repository;
    @Autowired
    private OutboundTaskWorker worker;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE outbound_task RESTART IDENTITY");
    }

    @Test
    void queuesOneTaskPerChatThatCaresAboutTheEnvironment() {
        assertThat(notifier.notify("PROD", "deploy:100", "INFRA deployed to PROD")).isEqualTo(2);

        assertThat(dedupKeys()).containsExactlyInAnyOrder(
                "telegram:deploy:100:-100prod",
                "telegram:deploy:100:-100all");
    }

    @Test
    void routeWithAnEnvironmentFilterIsSkippedForOtherStands() {
        assertThat(notifier.notify("DEV", "deploy:101", "INFRA deployed to DEV")).isEqualTo(1);

        assertThat(dedupKeys()).containsExactly("telegram:deploy:101:-100all");
    }

    @Test
    void environmentMatchingIgnoresCase() {
        assertThat(notifier.notify("prod", "deploy:102", "INFRA deployed to prod")).isEqualTo(2);
    }

    @Test
    void reprocessingTheSameDeploymentDoesNotQueueASecondMessage() {
        notifier.notify("PROD", "deploy:103", "INFRA deployed to PROD");
        assertThat(notifier.notify("PROD", "deploy:103", "INFRA deployed to PROD")).isZero();

        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void withTelegramDisabledTasksAreSkippedRatherThanSilentlyDropped() {
        // infra-manager.telegram.enabled по умолчанию false, поэтому отправитель не зарегистрирован.
        notifier.notify("PROD", "deploy:104", "INFRA deployed to PROD");

        worker.runOnce();

        assertThat(repository.findAll())
                .isNotEmpty()
                .allSatisfy(task -> {
                    assertThat(task.getStatus()).isEqualTo(ProcessingStatus.SKIPPED);
                    assertThat(task.getLastError()).contains("no sender");
                });
    }

    @Test
    void payloadCarriesTheRenderedTextAndChat() {
        notifier.notify("PROD", "deploy:105", "INFRA <b>deployed</b> to PROD");

        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM outbound_task WHERE dedup_key = ?",
                String.class, "telegram:deploy:105:-100prod");

        assertThat(payload).contains("INFRA <b>deployed</b> to PROD").contains("-100prod");
    }

    private List<String> dedupKeys() {
        return jdbc.queryForList("SELECT dedup_key FROM outbound_task", String.class);
    }
}
