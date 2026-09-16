package com.example.inframanager.deployment;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("infra-manager.bamboo")
public record BambooProperties(

        /**
         * По умолчанию NONE, чтобы сервис стартовал до того, как подключён Bamboo.
         * Переключите на WEBHOOK, когда появится шаблон вебхука, или на POLL, если в
         * этом Bamboo нет раздела Communication -> Webhook templates.
         */
        @DefaultValue("none") Source source,

        /** В том виде, в каком адрес доступен изнутри контейнера; см. docs/runbook.md. */
        @DefaultValue("") String baseUrl,

        @DefaultValue("") String token,

        /** Общий секрет, который шаблон вебхука присылает нам обратно. */
        @DefaultValue("") String webhookSecret,

        @DefaultValue("5s") Duration connectTimeout,

        @DefaultValue("15s") Duration readTimeout,

        /**
         * Деплои, завершившиеся раньше этого срока, записываются, но не объявляются.
         *
         * <p>Без этого первый же опрос живого Bamboo счёл бы новостью каждый доступный
         * ему исторический результат и завалил бы чат — по десять результатов на
         * окружение, и так по всем настроенным окружениям. Это же прикрывает и обратный
         * случай: после суток простоя сервиса никто не хочет получить вчерашние деплои
         * разом.
         */
        @DefaultValue("1h") Duration maxNotificationAge,

        /**
         * Id проекта деплоя → отображаемое имя. Нужно, только когда шаблон вебхука
         * Bamboo не умеет отрендерить имя проекта: старые шаблоны отдают лишь id.
         */
        @DefaultValue Map<Long, String> projectNames,

        @DefaultValue Poll poll) {

    public enum Source {

        /** Bamboo пушит к нам. Предпочтительно: нет нагрузки от опроса, почти мгновенно. */
        WEBHOOK,

        /** Мы спрашиваем Bamboo. Запасной вариант для версий без шаблонов вебхуков. */
        POLL,

        /** Bamboo ещё не подключён. */
        NONE
    }

    public record Poll(

            @DefaultValue("60s") Duration interval,

            /** Насколько глубоко смотрит каждый проход; что-то дают только не виденные раньше результаты. */
            @DefaultValue("10") int maxResults,

            @DefaultValue List<Environment> environments,

            @DefaultValue List<BuildPlan> buildPlans) {

        /**
         * Id окружений приходится перечислять явно. Вытаскивать их с дашборда деплоев —
         * это ещё одна форма ответа Bamboo, от которой мы бы зависели, а имена всё равно
         * нужны для сообщения.
         */
        public record Environment(long id, String projectName, String environmentName) {
        }

        /**
         * Билд-план, о провале которого стоит сообщить. Нужен там, где падение случается
         * раньше деплоя и потому в {@link Environment} не попадает: сборка, не дошедшая
         * до релиза, не порождает ни одного результата деплоя, а значит опрос окружений
         * её не увидит вовсе.
         *
         * @param key ключ плана вида {@code LIZA-REST}, а не номер конкретной сборки
         */
        public record BuildPlan(String key, String projectName, String environmentName) {
        }
    }
}
