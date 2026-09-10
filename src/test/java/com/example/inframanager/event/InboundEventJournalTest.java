package com.example.inframanager.event;

import java.util.ArrayList;
import java.util.List;

import com.example.inframanager.TestcontainersConfiguration;
import com.example.inframanager.deployment.DeploymentEventHandler;
import com.example.inframanager.pullrequest.PrCardService;
import com.example.inframanager.work.ProcessingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        // Drive the worker explicitly instead of racing the scheduler.
        "infra-manager.workers.scheduling-enabled=false",
        // Short enough to exhaust in a test.
        "infra-manager.workers.inbound.max-attempts=2"
})
@Import(TestcontainersConfiguration.class)
class InboundEventJournalTest {

    private static final String PAYLOAD = "{\"hello\":\"world\"}";

    /**
     * This test is about the journal, not about what the events mean. The real
     * handlers are mocked out so BambooOnlyHandler below is the only claimant of
     * BAMBOO -- without this, InboundEventProcessor rejects the ambiguity at startup
     * -- and BITBUCKET is left with no handler at all, which is the case one of the
     * tests below exercises.
     */
    @MockitoBean
    private DeploymentEventHandler deploymentEventHandler;

    @MockitoBean
    private PrCardService prCardService;

    @Autowired
    private InboundEventIngestService ingestService;
    @Autowired
    private InboundEventRepository repository;
    @Autowired
    private InboundEventWorker worker;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private BambooOnlyHandler handler;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE inbound_event RESTART IDENTITY");
        handler.handled.clear();
        handler.failure = null;
    }

    @Test
    void redeliveryOfTheSameWebhookIsIgnored() {
        assertThat(ingestService.ingest(EventSource.BAMBOO, "delivery-1", "deploy", PAYLOAD)).isTrue();
        assertThat(ingestService.ingest(EventSource.BAMBOO, "delivery-1", "deploy", PAYLOAD)).isFalse();

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void sameExternalIdFromADifferentSourceIsADistinctEvent() {
        assertThat(ingestService.ingest(EventSource.BAMBOO, "42", "deploy", PAYLOAD)).isTrue();
        assertThat(ingestService.ingest(EventSource.BITBUCKET, "42", "pr:opened", PAYLOAD)).isTrue();

        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void eventWithNoRegisteredHandlerIsSkippedRatherThanRetriedForever() {
        ingestService.ingest(EventSource.BITBUCKET, "pr-1", "pr:opened", PAYLOAD);

        assertThat(worker.runOnce()).isEqualTo(1);

        InboundEvent event = load(EventSource.BITBUCKET, "pr-1");
        assertThat(event.getStatus()).isEqualTo(ProcessingStatus.SKIPPED);
        assertThat(event.getLastError()).contains("no handler");
        assertThat(event.getProcessedAt()).isNotNull();
    }

    @Test
    void handledEventIsNotHandledASecondTime() {
        ingestService.ingest(EventSource.BAMBOO, "deploy-1", "deploy", PAYLOAD);

        assertThat(worker.runOnce()).isEqualTo(1);
        assertThat(worker.runOnce()).isZero();

        assertThat(handler.handled).hasSize(1);
        InboundEvent event = load(EventSource.BAMBOO, "deploy-1");
        assertThat(event.getStatus()).isEqualTo(ProcessingStatus.DONE);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).isNull();
    }

    @Test
    void failingHandlerBacksOffThenGivesUp() {
        handler.failure = new IllegalStateException("Trello is down");
        ingestService.ingest(EventSource.BAMBOO, "deploy-2", "deploy", PAYLOAD);

        worker.runOnce();

        InboundEvent afterFirst = load(EventSource.BAMBOO, "deploy-2");
        assertThat(afterFirst.getStatus()).isEqualTo(ProcessingStatus.PENDING);
        assertThat(afterFirst.getAttempts()).isEqualTo(1);
        assertThat(afterFirst.getLastError()).contains("Trello is down");

        // The backoff is real time; wind it back rather than sleeping through it.
        expireBackoff();
        worker.runOnce();

        InboundEvent afterSecond = load(EventSource.BAMBOO, "deploy-2");
        assertThat(afterSecond.getStatus()).isEqualTo(ProcessingStatus.FAILED);
        assertThat(afterSecond.getAttempts()).isEqualTo(2);
        assertThat(afterSecond.getProcessedAt()).isNotNull();
    }

    @Test
    void recoveredHandlerCompletesAPreviouslyFailingEvent() {
        handler.failure = new IllegalStateException("Trello is down");
        ingestService.ingest(EventSource.BAMBOO, "deploy-3", "deploy", PAYLOAD);
        worker.runOnce();

        handler.failure = null;
        expireBackoff();
        worker.runOnce();

        InboundEvent event = load(EventSource.BAMBOO, "deploy-3");
        assertThat(event.getStatus()).isEqualTo(ProcessingStatus.DONE);
        assertThat(event.getLastError()).isNull();
        assertThat(handler.handled).hasSize(1);
    }

    private void expireBackoff() {
        jdbc.update("UPDATE inbound_event SET next_attempt_at = now() - interval '1 hour'");
    }

    private InboundEvent load(EventSource source, String externalId) {
        return repository.findBySourceAndExternalId(source, externalId).orElseThrow();
    }

    /** Registered for BAMBOO only, so BITBUCKET events exercise the no-handler path. */
    static class BambooOnlyHandler implements InboundEventHandler {

        final List<String> handled = new ArrayList<>();
        volatile RuntimeException failure;

        @Override
        public boolean supports(EventSource source) {
            return source == EventSource.BAMBOO;
        }

        @Override
        public void handle(InboundEvent event) {
            if (failure != null) {
                throw failure;
            }
            handled.add(event.getExternalId());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Handlers {

        @Bean
        BambooOnlyHandler bambooOnlyHandler() {
            return new BambooOnlyHandler();
        }
    }
}
