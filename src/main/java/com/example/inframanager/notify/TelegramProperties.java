package com.example.inframanager.notify;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("infra-manager.telegram")
public record TelegramProperties(

        /**
         * При false бин отправителя вообще не регистрируется, поэтому поставленные в
         * очередь сообщения оседают в SKIPPED и остаются видны в {@code outbound_task},
         * а не исчезают.
         */
        @DefaultValue("false") boolean enabled,

        @DefaultValue("https://api.telegram.org") String baseUrl,

        String botToken,

        @DefaultValue("5s") Duration connectTimeout,

        /** Держим коротким: воркер удерживает блокировку строки на всё время вызова. */
        @DefaultValue("10s") Duration readTimeout,

        @DefaultValue List<Route> routes) {

    /** Куда уходит уведомление и какие стенды ему интересны. */
    public record Route(

            String chatId,

            /** Id топика внутри супергруппы-форума. Null для обычного чата. */
            Integer messageThreadId,

            /** Пусто означает «все окружения». */
            @DefaultValue List<String> environments) {

        public boolean matches(String environment) {
            if (environments.isEmpty()) {
                return true;
            }
            return environments.stream().anyMatch(e -> e.equalsIgnoreCase(environment));
        }
    }
}
