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
 * Запасной путь для случая, когда в Bamboo нет шаблонов вебхуков. Подменяет REST API,
 * поэтому проверяет фильтрацию и приведение к общей форме без всякой базы.
 */
class BambooDeploymentPollerTest {

    private static final String BASE = "https://bamboo.local";
    private static final String STAGE_URL = BASE + "/rest/api/latest/deploy/environment/10/results?max-results=5";
    private static final String PROD_URL = BASE + "/rest/api/latest/deploy/environment/20/results?max-results=5";

    private MockRestServiceServer server;
    private InboundEventIngestService ingestService;
    private BambooDeploymentPoller poller;

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

        poller = new BambooDeploymentPoller(client, properties(), ingestService, JsonMapper.builder().build());
    }

    @Test
    void finishedResultsAreIngestedAndInProgressOnesAreNot() {
        server.expect(requestTo(STAGE_URL)).andRespond(withSuccess("""
                {"results":[
                  {"id":501,"deploymentState":"SUCCESS","lifeCycleState":"FINISHED",
                   "deploymentVersionName":"release-9","startedDate":1757498400000,
                   "finishedDate":1757498430000,"reasonSummary":"Triggered by build"},
                  {"id":502,"deploymentState":"UNKNOWN","lifeCycleState":"IN_PROGRESS",
                   "deploymentVersionName":"release-10"}
                ]}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(PROD_URL)).andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        assertThat(poller.runOnce()).isEqualTo(1);

        ArgumentCaptor<String> externalId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(ingestService).ingest(eq(EventSource.BAMBOO), externalId.capture(), eq("deployment"), payload.capture());

        assertThat(externalId.getValue()).isEqualTo("501:SUCCESS");
        assertThat(payload.getValue())
                .contains("\"deploymentProjectName\":\"INFRA\"")
                .contains("\"environmentName\":\"STAGE\"")
                .contains("\"deploymentVersionName\":\"release-9\"")
                // Миллисекунды эпохи проходят насквозь строками; BambooDeploymentEvent
                // принимает и их, и ISO-8601.
                .contains("\"startedAt\":\"1757498400000\"");
    }

    @Test
    void oneUnreachableEnvironmentDoesNotStopTheOthers() {
        server.expect(requestTo(STAGE_URL)).andRespond(withServerError());
        server.expect(requestTo(PROD_URL)).andRespond(withSuccess("""
                {"results":[{"id":601,"deploymentState":"FAILED","lifeCycleState":"FINISHED"}]}
                """, MediaType.APPLICATION_JSON));

        assertThat(poller.runOnce()).isEqualTo(1);

        verify(ingestService).ingest(eq(EventSource.BAMBOO), eq("601:FAILED"), anyString(), anyString());
    }

    @Test
    void alreadySeenResultsAreNotCountedAsNew() {
        when(ingestService.ingest(any(), anyString(), anyString(), anyString())).thenReturn(false);
        server.expect(requestTo(STAGE_URL)).andRespond(withSuccess("""
                {"results":[{"id":701,"deploymentState":"SUCCESS","lifeCycleState":"FINISHED"}]}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(PROD_URL)).andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        assertThat(poller.runOnce()).isZero();
    }

    @Test
    void anEmptyResultsFieldIsNotAnError() {
        server.expect(requestTo(STAGE_URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(PROD_URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(poller.runOnce()).isZero();
        verify(ingestService, never()).ingest(any(), anyString(), anyString(), anyString());
    }

    private BambooProperties properties() {
        return new BambooProperties(
                BambooProperties.Source.POLL, BASE, "token", "", null, null, null, Map.of(),
                new BambooProperties.Poll(null, 5, List.of(
                        new BambooProperties.Poll.Environment(10, "INFRA", "STAGE"),
                        new BambooProperties.Poll.Environment(20, "INFRA", "PROD"))));
    }
}
