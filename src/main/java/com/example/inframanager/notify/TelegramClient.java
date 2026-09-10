package com.example.inframanager.notify;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Declarative client for the slice of the Bot API we use.
 *
 * <p>Only sending is covered. Receiving would mean {@code getUpdates} long polling,
 * which is deliberately out of scope: notifications need no inbound port, and
 * Telegram rejects concurrent {@code getUpdates} for one token with 409, so a poller
 * would have to be a strict singleton.
 *
 * <p>The bot token lives in the base URL ({@code /bot<token>}), so it never appears
 * in these signatures.
 */
public interface TelegramClient {

    @PostExchange("/sendMessage")
    SendMessageResponse sendMessage(@RequestBody SendMessageRequest request);

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SendMessageRequest(

            @JsonProperty("chat_id") String chatId,

            String text,

            @JsonProperty("parse_mode") String parseMode,

            @JsonProperty("message_thread_id") Integer messageThreadId,

            @JsonProperty("link_preview_options") LinkPreviewOptions linkPreviewOptions) {

        record LinkPreviewOptions(@JsonProperty("is_disabled") boolean isDisabled) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SendMessageResponse(

            boolean ok,

            @JsonProperty("error_code") Integer errorCode,

            String description) {
    }
}
