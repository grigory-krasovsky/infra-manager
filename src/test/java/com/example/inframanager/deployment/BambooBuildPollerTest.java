package com.example.inframanager.deployment;

import java.util.List;
import java.util.Map;

import com.example.inframanager.event.EventSource;
import com.example.inframanager.event.InboundEventIngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Ловит то, что {@link BambooDeploymentPollerTest} проверить не может: сборку, упавшую
 * раньше релиза, у которой результата деплоя нет вовсе.
 */
class BambooBuildPollerTest {

    private static final String BASE = "https://bamboo.local";
    private static final String REST_URL =
            BASE + "/rest/api/latest/result/LIZA-REST?max-results=5&expand=results.result";
    private static final String APIT_URL =
            BASE + "/rest/api/latest/result/LIZA-APIT?max-results=5&expand=results.result";

    private MockRestServiceServer server;
    private InboundEventIngestService ingestService;
    private BambooBuildPoller poller;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        BambooClient client = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(builder.build()))
                .build()
                .createClient(BambooClient.class);

        ingestService = mock(InboundEventIngestService.class);
        when(ingestService.ingest(any(), anyString(), anyString(), anyString())).thenReturn(true);

        poller = new BambooBuildPoller(client, properties(), ingestService, JsonMapper.builder().build());
    }

    @Test
    void onlyFinishedFailuresAreIngested() {
        server.expect(requestTo(REST_URL)).andRespond(withSuccess("""
                {"results":{"result":[
                  {"buildResultKey":"LIZA-REST-3081","buildState":"Failed","lifeCycleState":"Finished",
                   "buildStartedTime":"2026-09-16T09:00:00Z","buildCompletedTime":"2026-09-16T09:00:46Z",
                   "buildReason":"Changes by <a href=\\"https://bamboo.local/x\\">Красовский Григорий</a>"},
                  {"buildResultKey":"LIZA-REST-3079","buildState":"Successful","lifeCycleState":"Finished"},
                  {"buildResultKey":"LIZA-REST-3082","buildState":"Unknown","lifeCycleState":"InProgress"}
                ]}}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(APIT_URL)).andRespond(withSuccess("{\"results\":{\"result\":[]}}", MediaType.APPLICATION_JSON));

        assertThat(poller.runOnce()).isEqualTo(1);

        ArgumentCaptor<String> externalId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(ingestService).ingest(eq(EventSource.BAMBOO), externalId.capture(), eq("build"), payload.capture());

        assertThat(externalId.getValue()).isEqualTo("LIZA-REST-3081");
        assertThat(payload.getValue())
                .contains("\"buildResultKey\":\"LIZA-REST-3081\"")
                .contains("\"buildState\":\"Failed\"")
                .contains("\"projectName\":\"Лиза API\"")
                .contains("\"environmentName\":\"dev\"")
                .contains("\"resultUrl\":\"https://bamboo.local/browse/LIZA-REST-3081\"")
                // Без expand=results.result Bamboo отдаёт голый ключ и статус — время
                // завершения отсутствует, и защита от старых результатов не срабатывает.
                .contains("\"startedAt\":\"2026-09-16T09:00:00Z\"")
                .contains("\"finishedAt\":\"2026-09-16T09:00:46Z\"")
                .contains("\"buildReason\":\"Changes by");
    }

    @Test
    void oneUnreachablePlanDoesNotStopTheOthers() {
        server.expect(requestTo(REST_URL)).andRespond(withServerError());
        server.expect(requestTo(APIT_URL)).andRespond(withSuccess("""
                {"results":{"result":[
                  {"buildResultKey":"LIZA-APIT-100","buildState":"Failed","lifeCycleState":"Finished"}
                ]}}
                """, MediaType.APPLICATION_JSON));

        assertThat(poller.runOnce()).isEqualTo(1);

        verify(ingestService).ingest(eq(EventSource.BAMBOO), eq("LIZA-APIT-100"), eq("build"), anyString());
    }

    @Test
    void alreadySeenFailuresAreNotCountedAsNew() {
        when(ingestService.ingest(any(), anyString(), anyString(), anyString())).thenReturn(false);
        server.expect(requestTo(REST_URL)).andRespond(withSuccess("""
                {"results":{"result":[
                  {"buildResultKey":"LIZA-REST-3081","buildState":"Failed","lifeCycleState":"Finished"}
                ]}}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(APIT_URL)).andRespond(withSuccess("{\"results\":{\"result\":[]}}", MediaType.APPLICATION_JSON));

        assertThat(poller.runOnce()).isZero();
    }

    @Test
    void anEmptyResultsFieldIsNotAnError() {
        server.expect(requestTo(REST_URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(APIT_URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(poller.runOnce()).isZero();
        verify(ingestService, never()).ingest(any(), anyString(), anyString(), anyString());
    }

    private BambooProperties properties() {
        return new BambooProperties(
                BambooProperties.Source.POLL, BASE, "token", "", null, null, null, Map.of(),
                new BambooProperties.Poll(null, 5, List.of(), List.of(
                        new BambooProperties.Poll.BuildPlan("LIZA-REST", "Лиза API", "dev"),
                        new BambooProperties.Poll.BuildPlan("LIZA-APIT", "Лиза API", "test"))));
    }
}
