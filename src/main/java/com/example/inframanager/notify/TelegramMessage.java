package com.example.inframanager.notify;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * What gets stored in {@code outbound_task.payload} for a TELEGRAM task: one
 * message to one chat, already rendered. Routing is resolved when the task is
 * queued, not when it is sent, so a config change mid-queue cannot silently
 * redirect a message that was already decided.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramMessage(String chatId, Integer messageThreadId, String text) {
}
