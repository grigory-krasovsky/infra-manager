package com.example.inframanager.jira;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("infra-manager.jira")
public record JiraProperties(

        /** Чистая косметика: при выключенной Jira карточки озаглавливаются только по пул-реквесту. */
        @DefaultValue("false") boolean enabled,

        @DefaultValue("") String baseUrl,

        /** Personal access token Data Center, отправляется как bearer-токен. */
        @DefaultValue("") String token,

        /**
         * Намеренно короткий. Этот вызов происходит, пока входящий воркер держит
         * блокировку строки, и медленная Jira не должна тормозить события пул-реквестов:
         * отсутствующее summary — куда меньшая беда, чем вставшая очередь.
         */
        @DefaultValue("2s") Duration connectTimeout,

        @DefaultValue("3s") Duration readTimeout,

        /** Сколько полученное summary переиспользуется, прежде чем спросить Jira снова. */
        @DefaultValue("1h") Duration cacheTtl,

        /**
         * Отлавливает ключи задач в именах веток и заголовках пул-реквестов. По умолчанию —
         * стандартная форма Atlassian; ужмите под ключи своих проектов, если в именах веток
         * встречается что-то ещё, похожее на ключ.
         */
        @DefaultValue("([A-Z][A-Z0-9]+-\\d+)") String issueKeyPattern,

        /**
         * Адрес Jira для ссылок, которые прочтёт человек. Отделён от {@code baseUrl}
         * потому, что тот — адрес, по которому в Jira ходит контейнер, и совпадать они не
         * обязаны. Нужен и при выключенной интеграции: ссылку на задачу можно поставить,
         * ничего у Jira не спрашивая.
         */
        @DefaultValue("") String browseUrl) {

    /** @return адрес для ссылок; пусто, если не настроен ни он, ни базовый */
    public String browseUrlOrBase() {
        String url = browseUrl == null || browseUrl.isBlank() ? baseUrl : browseUrl;
        return url == null ? "" : url.replaceAll("/+$", "");
    }
}
