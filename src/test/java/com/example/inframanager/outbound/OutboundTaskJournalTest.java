package com.example.inframanager.outbound;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.example.inframanager.TestcontainersConfiguration;
import com.example.inframanager.work.ProcessingStatus;
import com.example.inframanager.work.RetryAfterException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "infra-manager.workers.scheduling-enabled=false",
        "infra-manager.workers.outbound.max-attempts=2"
})
@Import(TestcontainersConfiguration.class)
class OutboundTaskJournalTest {

    private static final String PAYLOAD = "{\"text\":\"deployed\"}";

    @Autowired
    private OutboundTaskService taskService;
    @Autowired
    private OutboundTaskRepository repository;
    @Autowired
    private OutboundTaskWorker worker;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TelegramOnlySender sender;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE outbound_task RESTART IDENTITY");
        sender.sent.clear();
        sender.failure = null;
    }

    @Test
    void enqueuingTheSameDedupKeyTwiceQueuesOneTask() {
        assertThat(taskService.enqueue(OutboundTarget.TELEGRAM, "sendMessage", "deploy:77", PAYLOAD)).isTrue();
        assertThat(taskService.enqueue(OutboundTarget.TELEGRAM, "sendMessage", "deploy:77", PAYLOAD)).isFalse();

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void taskWithNoRegisteredSenderIsSkipped() {
        taskService.enqueue(OutboundTarget.TRELLO, "createCard", "pr:1", PAYLOAD);

        assertThat(worker.runOnce()).isEqualTo(1);

        OutboundTask task = load("pr:1");
        assertThat(task.getStatus()).isEqualTo(ProcessingStatus.SKIPPED);
        assertThat(task.getLastError()).contains("no sender");
    }

    @Test
    void sentTaskIsNotSentAgain() {
        taskService.enqueue(OutboundTarget.TELEGRAM, "sendMessage", "deploy:1", PAYLOAD);

        assertThat(worker.runOnce()).isEqualTo(1);
        assertThat(worker.runOnce()).isZero();

        assertThat(sender.sent).containsExactly("deploy:1");
        OutboundTask task = load("deploy:1");
        assertThat(task.getStatus()).isEqualTo(ProcessingStatus.DONE);
        assertThat(task.getCompletedAt()).isNotNull();
    }

    @Test
    void rateLimitDefersWithoutSpendingAnAttempt() {
        sender.failure = new RetryAfterException("429 from Telegram", Duration.ofMinutes(5));
        taskService.enqueue(OutboundTarget.TELEGRAM, "sendMessage", "deploy:2", PAYLOAD);

        worker.runOnce();

        OutboundTask task = load("deploy:2");
        assertThat(task.getStatus()).isEqualTo(ProcessingStatus.PENDING);
        // Being told to slow down is not a failed attempt; otherwise a busy period
        // would burn the retry budget and drop notifications.
        assertThat(task.getAttempts()).isZero();
        assertThat(task.getNextAttemptAt()).isAfter(Instant.now().plus(Duration.ofMinutes(4)));
    }

    @Test
    void realFailureBacksOffThenGivesUp() {
        sender.failure = new IllegalStateException("bot token rejected");
        taskService.enqueue(OutboundTarget.TELEGRAM, "sendMessage", "deploy:3", PAYLOAD);

        worker.runOnce();
        assertThat(load("deploy:3").getStatus()).isEqualTo(ProcessingStatus.PENDING);
        assertThat(load("deploy:3").getAttempts()).isEqualTo(1);

        expireBackoff();
        worker.runOnce();

        OutboundTask task = load("deploy:3");
        assertThat(task.getStatus()).isEqualTo(ProcessingStatus.FAILED);
        assertThat(task.getAttempts()).isEqualTo(2);
        assertThat(task.getLastError()).contains("bot token rejected");
    }

    private void expireBackoff() {
        jdbc.update("UPDATE outbound_task SET next_attempt_at = now() - interval '1 hour'");
    }

    private OutboundTask load(String dedupKey) {
        return repository.findByDedupKey(dedupKey).orElseThrow();
    }

    /** Registered for TELEGRAM only, so TRELLO tasks exercise the no-sender path. */
    static class TelegramOnlySender implements OutboundTaskSender {

        final List<String> sent = new ArrayList<>();
        volatile RuntimeException failure;

        @Override
        public OutboundTarget target() {
            return OutboundTarget.TELEGRAM;
        }

        @Override
        public void send(OutboundTask task) {
            if (failure != null) {
                throw failure;
            }
            sent.add(task.getDedupKey());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Senders {

        @Bean
        TelegramOnlySender telegramOnlySender() {
            return new TelegramOnlySender();
        }
    }
}
