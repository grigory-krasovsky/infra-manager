package com.example.inframanager.notify;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * То, что лежит в {@code outbound_task.payload} для задачи TELEGRAM: одно сообщение в
 * один чат, уже отрендеренное. Маршрутизация разрешается в момент постановки задачи в
 * очередь, а не в момент отправки, поэтому смена конфигурации на полпути не может молча
 * перенаправить сообщение, судьба которого уже решена.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramMessage(String chatId, Integer messageThreadId, String text) {
}
