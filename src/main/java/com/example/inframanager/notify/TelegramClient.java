package com.example.inframanager.notify;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Декларативный клиент для того куска Bot API, которым мы пользуемся.
 *
 * <p>Покрыта только отправка. Приём означал бы long polling через {@code getUpdates},
 * а это намеренно вне рамок задачи: уведомлениям входящий порт не нужен, а параллельные
 * {@code getUpdates} по одному токену Telegram отклоняет с 409, так что поллер пришлось
 * бы держать строго в одном экземпляре.
 *
 * <p>Токен бота живёт в базовом URL ({@code /bot<token>}), поэтому в этих сигнатурах он
 * не появляется.
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
