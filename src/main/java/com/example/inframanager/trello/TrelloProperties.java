package com.example.inframanager.trello;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
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

        /**
         * Имя метки → цвет из палитры Trello. Метку, которой здесь нет, красит первый
         * свободный на доске цвет; названной же цвет и назначается, и восстанавливается,
         * если он на доске другой — иначе метки, созданные до этой настройки, остались бы
         * с прежними цветами навсегда.
         *
         * <p>Список пар, а не map: в именах меток встречаются точки и «/» (это ещё и
         * имена веток), а такой ключ property Spring разбирает по-своему.
         */
        @DefaultValue List<LabelColor> labelColors,

        /**
         * Размер обложки — цветного фона карточки: {@code normal} — полоса над заголовком,
         * {@code full} — заливка всей карточки, поверх которой идёт текст. Общий для всех
         * обложек, потому что размером здесь ничего не сказано: разный размер у соседних
         * карточек читается как сбой, а не как признак. Цвет же задаётся репозиторию,
         * в {@code infra-manager.lifecycle.repos}.
         */
        @DefaultValue("normal") String coverSize,

        @DefaultValue Reconciliation reconciliation,

        @DefaultValue Completion completion) {

    /** Других размеров обложки Trello не знает. */
    static final List<String> COVER_SIZES = List.of("normal", "full");

    /**
     * Незнакомый размер Trello принимает и рисует обложку по-своему — то есть опечатка
     * выглядит как работающая настройка. Ловим при старте, как и незнакомый цвет.
     */
    public TrelloProperties {
        coverSize = coverSize == null || coverSize.isBlank()
                ? "normal"
                : coverSize.trim().toLowerCase(Locale.ROOT);
        if (!COVER_SIZES.contains(coverSize)) {
            throw new IllegalArgumentException(
                    "infra-manager.trello.cover-size: '%s' is not a Trello cover size; allowed: %s"
                            .formatted(coverSize, COVER_SIZES));
        }
    }

    public record LabelColor(String label, String color) {
    }

    /**
     * Карточка пул-реквеста, закрытого дольше {@code after} назад, отмечается в Trello
     * выполненной. Влит он или отклонён, неважно: и там и там решение принято, и делать
     * вид, что работа ещё идёт, доске незачем.
     *
     * <p>Колонку карточка при этом не покидает: колонка отвечает на вопрос «чем
     * кончилось», и от того, что прошла неделя, ответ не меняется.
     */
    public record Completion(

            @DefaultValue("false") boolean enabled,

            /** Возраст пул-реквеста меряется днями — чаще раза в сутки смотреть не на что. */
            @DefaultValue("24h") Duration interval,

            /** Сколько должно пройти с закрытия пул-реквеста, чтобы карточка считалась завершённой. */
            @DefaultValue("7d") Duration after) {
    }

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
