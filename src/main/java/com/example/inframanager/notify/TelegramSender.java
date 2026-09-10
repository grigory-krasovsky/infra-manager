package com.example.inframanager.notify;

import java.time.Duration;

import com.example.inframanager.outbound.OutboundTarget;
import com.example.inframanager.outbound.OutboundTask;
import com.example.inframanager.outbound.OutboundTaskSender;
import com.example.inframanager.work.RetryAfterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Выполняет исходящие задачи TELEGRAM. Создаётся в {@link TelegramClientConfig},
 * который существует, только когда Telegram включён.
 */
public class TelegramSender implements OutboundTaskSender {

    private static final Logger log = LoggerFactory.getLogger(TelegramSender.class);

    /** Используется, когда Telegram отвечает 429, но не говорит, на сколько. */
    private static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(30);

    private final TelegramClient client;
    private final ObjectMapper objectMapper;

    public TelegramSender(TelegramClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public OutboundTarget target() {
        return OutboundTarget.TELEGRAM;
    }

    @Override
    public void send(OutboundTask task) {
        TelegramMessage message = objectMapper.readValue(task.getPayload(), TelegramMessage.class);

        TelegramClient.SendMessageRequest request = new TelegramClient.SendMessageRequest(
                message.chatId(),
                message.text(),
                "HTML",
                message.messageThreadId(),
                new TelegramClient.SendMessageRequest.LinkPreviewOptions(true));

        TelegramClient.SendMessageResponse response;
        try {
            response = client.sendMessage(request);
        } catch (HttpClientErrorException.TooManyRequests e) {
            throw new RetryAfterException(
                    "Telegram rate limited chat " + message.chatId(), retryAfterFrom(e));
        }

        // Обычно Telegram сообщает о сбое статусом вне 2xx, но в конверте есть и
        // собственный флаг ok; считаем его false сбоем, а не рапортуем об успешной
        // отправке сообщения, которое на самом деле не доставили.
        if (response == null || !response.ok()) {
            throw new IllegalStateException("Telegram rejected sendMessage for chat %s: %s"
                    .formatted(message.chatId(), response == null ? "empty response" : response.description()));
        }
        log.debug("Sent Telegram message to chat {}", message.chatId());
    }

    private Duration retryAfterFrom(HttpClientErrorException e) {
        // Предпочтительный источник: {"parameters":{"retry_after":30}} в конверте ошибки.
        try {
            JsonNode body = objectMapper.readTree(e.getResponseBodyAsString());
            JsonNode retryAfter = body.path("parameters").path("retry_after");
            if (retryAfter.isNumber()) {
                return Duration.ofSeconds(retryAfter.asLong());
            }
        } catch (RuntimeException parseFailure) {
            log.debug("Could not read retry_after from Telegram 429 body", parseFailure);
        }

        long headerSeconds = e.getResponseHeaders() == null ? 0
                : e.getResponseHeaders().getFirst("Retry-After") == null ? 0
                : parseLongOrZero(e.getResponseHeaders().getFirst("Retry-After"));
        return headerSeconds > 0 ? Duration.ofSeconds(headerSeconds) : DEFAULT_RETRY_AFTER;
    }

    private static long parseLongOrZero(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
