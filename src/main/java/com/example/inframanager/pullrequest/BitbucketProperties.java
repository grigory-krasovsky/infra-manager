package com.example.inframanager.pullrequest;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

@ConfigurationProperties("infra-manager.bitbucket")
public record BitbucketProperties(

        /** Открывает /webhooks/bitbucket. Выключено, пока нет секрета вебхука. */
        @DefaultValue("false") boolean enabled,

        /** В том виде, в каком адрес доступен изнутри контейнера. */
        @DefaultValue("") String baseUrl,

        /**
         * URL, по которому кликает человек. Обычно совпадает с {@link #baseUrl}, но они
         * расходятся, когда контейнер ходит в Bitbucket через host.docker.internal.
         */
        @DefaultValue("") String browseUrl,

        @DefaultValue("") String token,

        /** Общий с вебхуком Bitbucket; используется для HMAC в X-Hub-Signature. */
        @DefaultValue("") String webhookSecret,

        @DefaultValue("5s") Duration connectTimeout,

        @DefaultValue("15s") Duration readTimeout,

        @DefaultValue Poll poll) {

    /**
     * Поллинг заменяет вебхуки, когда Bitbucket не может открыть соединение к нам, —
     * обычная ситуация, если сервис работает вне корпоративной сети. Репозитории берутся
     * из {@code infra-manager.lifecycle.repos}: опрос репозитория, который мы не зеркалим,
     * породил бы события, на которые никто не реагирует.
     */
    public record Poll(

            @DefaultValue("false") boolean enabled,

            @DefaultValue("2m") Duration interval,

            /** Сколько пул-реквестов читаем за проход по репозиторию, начиная с самых свежих. */
            @DefaultValue("50") int maxResults) {
    }

    public String effectiveBrowseUrl() {
        return StringUtils.hasText(browseUrl) ? browseUrl : baseUrl;
    }
}
