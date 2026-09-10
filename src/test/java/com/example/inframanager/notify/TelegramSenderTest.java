package com.example.inframanager.notify;

import java.time.Duration;

import com.example.inframanager.outbound.OutboundTask;
import com.example.inframanager.work.RetryAfterException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Exercises the sender against a stubbed Bot API -- no container needed, since the
 * interesting behaviour is entirely in how responses are interpreted.
 */
class TelegramSenderTest {

    private static final String SEND_URL = "https://api.telegram.org/botTEST-TOKEN/sendMessage";

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private MockRestServiceServer server;
    private TelegramSender sender;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.telegram.org/botTEST-TOKEN");
        server = MockRestServiceServer.bindTo(builder).build();
        TelegramClient client = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(builder.build()))
                .build()
                .createClient(TelegramClient.class);
        sender = new TelegramSender(client, objectMapper);
    }

    @Test
    void sendsTheRenderedTextToTheChatFromThePayload() {
        server.expect(requestTo(SEND_URL))
                .andExpect(jsonPath("$.chat_id").value("-100123"))
                .andExpect(jsonPath("$.text").value("INFRA deployed to STAGE"))
                .andExpect(jsonPath("$.parse_mode").value("HTML"))
                .andRespond(withSuccess("{\"ok\":true,\"result\":{\"message_id\":7}}",
                        MediaType.APPLICATION_JSON));

        sender.send(task(new TelegramMessage("-100123", null, "INFRA deployed to STAGE")));

        server.verify();
    }

    @Test
    void includesTheTopicIdWhenTheRouteHasOne() {
        server.expect(requestTo(SEND_URL))
                .andExpect(jsonPath("$.message_thread_id").value(42))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        sender.send(task(new TelegramMessage("-100123", 42, "hello")));

        server.verify();
    }

    @Test
    void rateLimitIsReportedWithTheDelayTelegramAskedFor() {
        server.expect(requestTo(SEND_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"ok":false,"error_code":429,
                                 "description":"Too Many Requests: retry after 42",
                                 "parameters":{"retry_after":42}}
                                """));

        assertThatThrownBy(() -> sender.send(task(new TelegramMessage("-100123", null, "hi"))))
                .isInstanceOf(RetryAfterException.class)
                .extracting(e -> ((RetryAfterException) e).getRetryAfter())
                .isEqualTo(Duration.ofSeconds(42));
    }

    @Test
    void rateLimitWithoutADelayFallsBackToADefault() {
        server.expect(requestTo(SEND_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"ok\":false,\"error_code\":429}"));

        assertThatThrownBy(() -> sender.send(task(new TelegramMessage("-100123", null, "hi"))))
                .isInstanceOf(RetryAfterException.class)
                .extracting(e -> ((RetryAfterException) e).getRetryAfter())
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void okFalseIsTreatedAsAFailureRatherThanASend() {
        server.expect(requestTo(SEND_URL))
                .andRespond(withSuccess("{\"ok\":false,\"description\":\"chat not found\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> sender.send(task(new TelegramMessage("-100123", null, "hi"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chat not found");
    }

    private OutboundTask task(TelegramMessage message) {
        OutboundTask task = new OutboundTask();
        task.setPayload(objectMapper.writeValueAsString(message));
        return task;
    }

    @Test
    void targetIsTelegram() {
        assertThat(sender.target()).isEqualTo(com.example.inframanager.outbound.OutboundTarget.TELEGRAM);
    }
}
