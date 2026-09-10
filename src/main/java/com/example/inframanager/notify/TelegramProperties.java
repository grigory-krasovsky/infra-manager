package com.example.inframanager.notify;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("infra-manager.telegram")
public record TelegramProperties(

        /**
         * When false the sender bean is not registered at all, so queued messages land
         * in SKIPPED and stay visible in {@code outbound_task} instead of vanishing.
         */
        @DefaultValue("false") boolean enabled,

        @DefaultValue("https://api.telegram.org") String baseUrl,

        String botToken,

        @DefaultValue("5s") Duration connectTimeout,

        /** Kept short: the worker holds a row lock for the duration of the call. */
        @DefaultValue("10s") Duration readTimeout,

        @DefaultValue List<Route> routes) {

    /** Where a notification goes, and which stands it cares about. */
    public record Route(

            String chatId,

            /** Topic id inside a forum-style supergroup. Null for a plain chat. */
            Integer messageThreadId,

            /** Empty means every environment. */
            @DefaultValue List<String> environments) {

        public boolean matches(String environment) {
            if (environments.isEmpty()) {
                return true;
            }
            return environments.stream().anyMatch(e -> e.equalsIgnoreCase(environment));
        }
    }
}
