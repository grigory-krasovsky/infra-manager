package com.example.inframanager.trello;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("infra-manager.trello")
public record TrelloProperties(

        /** Пока false, задачи по карточкам ставятся в очередь и помечаются SKIPPED, а не теряются. */
        @DefaultValue("false") boolean enabled,

        @DefaultValue("https://api.trello.com") String baseUrl,

        /**
         * Со вкладки API Key у Power-Up на https://trello.com/apps/admin — с 2024 года
         * получить его иначе нельзя. Не секрет.
         */
        @DefaultValue("") String key,

        /** Даёт доступ к доскам авторизовавшей учётной записи. Вот это уже секрет. */
        @DefaultValue("") String token,

        @DefaultValue("5s") Duration connectTimeout,

        @DefaultValue("10s") Duration readTimeout,

        /** Сколько мы доверяем соответствию «имя списка → id списка» до повторного запроса. */
        @DefaultValue("10m") Duration listCacheTtl,

        /**
         * Логин или имя из Bitbucket → участник Trello (id, username или полное имя).
         * Заполнять нужно только исключения: обычно человека находит сравнение имён, а
         * запись здесь нужна тем, у кого в профиле Trello стоит не настоящее имя, и тем,
         * чьё имя совпало сразу с несколькими участниками доски. Тот, кого здесь нет и
         * кого не нашли по имени, просто оставляет карточку без исполнителя.
         */
        @DefaultValue Map<String, String> members,

        @DefaultValue Reconciliation reconciliation) {

    /**
     * Ловит то, чего односторонняя синхронизация не видит: карточку, которую кто-то
     * перетащил руками или удалил совсем. Это единственная причина вообще читать из
     * Trello — и делать это приходится опросом, потому что вебхукам Trello нужен
     * callback-URL, до которого Trello достучится, а внутренний хост таким не является.
     */
    public record Reconciliation(

            @DefaultValue("false") boolean enabled,

            @DefaultValue("15m") Duration interval,

            /** По умолчанию — сообщить, а не бороться с человеком, переместившим карточку. */
            @DefaultValue("log") OnDrift onDrift,

            @DefaultValue("log") OnMissing onMissing) {

        public enum OnDrift {

            /** Сообщить о расхождении и оставить карточку там, куда её положил человек. */
            LOG,

            /** Поставить в очередь возврат туда, где карточке место по нашему состоянию. */
            RESTORE
        }

        public enum OnMissing {

            LOG,

            /** Забыть id карточки, чтобы следующее событие по этому PR создало новую. */
            FORGET
        }
    }
}
