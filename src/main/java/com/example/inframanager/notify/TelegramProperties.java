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

        /**
         * Прокси только для Telegram. Общесистемные {@code https.proxyHost} не годятся:
         * они увели бы в тот же прокси и Bitbucket с Bamboo, а те живут во внутренней
         * сети, куда через прокси хода нет. Пусто — ходим напрямую.
         */
        @DefaultValue Proxy proxy,

        /**
         * В каком поясе показывать время в уведомлениях. Хранится всё в UTC, но читают
         * сообщения люди, и «10.09.2026 18:16» им понятнее, чем то же самое в UTC.
         */
        @DefaultValue("Europe/Moscow") String timeZone,

        @DefaultValue List<Route> routes) {

    /**
     * @param type     {@code http} или {@code socks5}. Это не оттенок одной настройки, а
     *                 два разных механизма: HTTP-прокси получает CONNECT-запрос, SOCKS
     *                 работает уровнем ниже, на сокете. От типа зависит, какой клиент
     *                 вообще способен через него пройти, поэтому угадывать его мы не
     *                 беремся.
     * @param username только для HTTP-прокси; SOCKS-авторизация не поддержана за
     *                 отсутствием спроса
     */
    public record Proxy(@DefaultValue("http") Type type,
                        String host,
                        @DefaultValue("0") int port,
                        String username,
                        String password) {

        public enum Type {

            HTTP,

            SOCKS5
        }

        public boolean isConfigured() {
            return host != null && !host.isBlank() && port > 0;
        }

        public boolean needsAuthentication() {
            return isConfigured() && type == Type.HTTP && username != null && !username.isBlank();
        }
    }

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
