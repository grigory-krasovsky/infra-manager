package com.example.inframanager.deployment;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.util.StringUtils;

/**
 * Каноническая форма результата деплоя, которую пишут оба пути приёма: контроллер
 * вебхука сохраняет тело, собранное нашим же шаблоном Bamboo
 * (docs/bamboo-webhook-template.json), а поллер приводит ответы REST к той же форме.
 * Всё, что дальше по цепочке, видит благодаря этому один формат.
 *
 * <p>Метки времени приходят строками, потому что два источника расходятся: REST API
 * Bamboo возвращает миллисекунды эпохи, а шаблон Velocity рендерит то, что даёт формат
 * даты. {@link #parseInstant} принимает и то и другое.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BambooDeploymentEvent(

        Long deploymentResultId,

        /** {@code deploymentState} из Bamboo: SUCCESS, FAILED, UNKNOWN. */
        String status,

        /** {@code lifeCycleState} из Bamboo: QUEUED, IN_PROGRESS, FINISHED. В вебхуках отсутствует. */
        String lifeCycleState,

        String deploymentProjectName,

        /**
         * Есть, если шаблон вебхука его отдаёт. Старые шаблоны Bamboo предлагают только
         * id, поэтому отображаемое имя может подставить
         * {@code infra-manager.bamboo.project-names}.
         */
        Long deploymentProjectId,

        String environmentName,

        String deploymentVersionName,

        String startedAt,

        String finishedAt,

        String triggerSentence) {

    private static final String UNKNOWN = "UNKNOWN";

    public String normalisedStatus() {
        return StringUtils.hasText(status) ? status.toUpperCase() : UNKNOWN;
    }

    public boolean isSuccess() {
        return "SUCCESS".equalsIgnoreCase(status);
    }

    /** Вебхуки срабатывают по завершении и поля lifecycle не присылают, поэтому его отсутствие означает «завершено». */
    public boolean isFinished() {
        return !StringUtils.hasText(lifeCycleState) || "FINISHED".equalsIgnoreCase(lifeCycleState);
    }

    /** Деплои в очереди и в процессе записываются, но не объявляются. */
    public boolean isNotifiable() {
        return isFinished() && !UNKNOWN.equals(normalisedStatus());
    }

    public String projectNameOrUnknown() {
        return StringUtils.hasText(deploymentProjectName) ? deploymentProjectName : UNKNOWN;
    }

    public boolean hasProjectName() {
        return StringUtils.hasText(deploymentProjectName);
    }

    public BambooDeploymentEvent withProjectName(String projectName) {
        return new BambooDeploymentEvent(deploymentResultId, status, lifeCycleState, projectName,
                deploymentProjectId, environmentName, deploymentVersionName, startedAt, finishedAt,
                triggerSentence);
    }

    public String environmentNameOrUnknown() {
        return StringUtils.hasText(environmentName) ? environmentName : UNKNOWN;
    }

    /** Названо иначе, чем компонент {@code startedAt}, потому что аксессор record'а не может сменить тип. */
    public Instant startedInstant() {
        return parseInstant(startedAt);
    }

    public Instant finishedInstant() {
        return parseInstant(finishedAt);
    }

    /** Null, если один из концов отсутствует или не разбирается, — сообщение просто обойдётся без этого. */
    public Duration duration() {
        Instant from = startedInstant();
        Instant to = finishedInstant();
        if (from == null || to == null || to.isBefore(from)) {
            return null;
        }
        return Duration.between(from, to);
    }

    static Instant parseInstant(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return Instant.ofEpochMilli(Long.parseLong(trimmed));
        } catch (NumberFormatException notEpochMillis) {
            // Проваливаемся к ISO-8601.
        }
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException notIso) {
            return null;
        }
    }
}
