package com.example.inframanager.deployment;

/**
 * Задача, ради которой был деплой: ключ, название и адрес в Jira.
 *
 * @param url может быть null, если адрес Jira не настроен — тогда ключ остаётся текстом
 */
public record DeploymentIssue(String key, String summary, String url) {
}
