package com.example.inframanager.jira;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Смысл этих тестов в том, что отсюда никогда не летит исключение: обогащение — это
 * украшение, и сломанная Jira не должна уметь помешать созданию карточки.
 */
class JiraEnricherTest {

    private static final String ISSUE_URL = "https://jira.local/rest/api/2/issue/PROJ-1?fields=summary";

    private MockRestServiceServer server;
    private JiraEnricher enricher;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://jira.local");
        server = MockRestServiceServer.bindTo(builder).build();
        JiraClient client = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(builder.build()))
                .build()
                .createClient(JiraClient.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<JiraClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);

        enricher = new JiraEnricher(provider, properties(true));
    }

    @Test
    void returnsTheSummaryForAKnownIssue() {
        server.expect(requestTo(ISSUE_URL)).andRespond(withSuccess(
                "{\"key\":\"PROJ-1\",\"fields\":{\"summary\":\"Починить деплой\"}}",
                MediaType.APPLICATION_JSON));

        assertThat(enricher.summaryFor("PROJ-1")).contains("Починить деплой");
    }

    @Test
    void anUnknownIssueYieldsNothingRatherThanAnError() {
        server.expect(requestTo(ISSUE_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(enricher.summaryFor("PROJ-1")).isEmpty();
    }

    @Test
    void aBrokenJiraYieldsNothingRatherThanAnError() {
        server.expect(requestTo(ISSUE_URL)).andRespond(withServerError());

        assertThat(enricher.summaryFor("PROJ-1")).isEmpty();
    }

    @Test
    void aSecondLookupOfTheSameIssueIsServedFromCache() {
        server.expect(requestTo(ISSUE_URL)).andRespond(withSuccess(
                "{\"key\":\"PROJ-1\",\"fields\":{\"summary\":\"Once\"}}", MediaType.APPLICATION_JSON));

        assertThat(enricher.summaryFor("PROJ-1")).contains("Once");
        assertThat(enricher.summaryFor("PROJ-1")).contains("Once");

        // Заглушен ровно один запрос; второй вызов не прошёл бы проверку.
        server.verify();
    }

    @Test
    void aFailureIsAlsoCachedSoAnOutageDoesNotBecomeAStormOfCalls() {
        server.expect(requestTo(ISSUE_URL)).andRespond(withServerError());

        assertThat(enricher.summaryFor("PROJ-1")).isEmpty();
        assertThat(enricher.summaryFor("PROJ-1")).isEmpty();

        server.verify();
    }

    @Test
    void nothingIsCalledWhenJiraIsDisabled() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JiraClient> provider = mock(ObjectProvider.class);
        JiraEnricher disabled = new JiraEnricher(provider, properties(false));

        assertThat(disabled.summaryFor("PROJ-1")).isEmpty();
    }

    @Test
    void aMissingKeyIsNotLookedUp() {
        assertThat(enricher.summaryFor(null)).isEmpty();
        assertThat(enricher.summaryFor("")).isEmpty();
    }

    private JiraProperties properties(boolean enabled) {
        return new JiraProperties(enabled, "https://jira.local", "token",
                Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofHours(1),
                "([A-Z][A-Z0-9]+-\\d+)", "");
    }
}
