package com.example.inframanager.deployment;

import java.time.Duration;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.util.StringUtils;

/**
 * Провалившаяся сборка, которую записывает поллер {@link BambooBuildPoller} — единственный
 * путь приёма: у сборки, не дошедшей до релиза, нет ни одного результата деплоя, а значит
 * и вебхука деплоя, который мог бы её принести.
 *
 * <p>{@code projectName} и {@code environmentName} — не то, как называет себя Bamboo, а
 * то, что задано в {@code infra-manager.bamboo.poll.build-plans}: поллер подставляет их
 * ещё до сохранения, так же как {@link BambooDeploymentPoller} делает для результатов
 * деплоя, — единый формат экономит обработчику лишнюю подстановку.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BambooBuildEvent(

        String buildResultKey,

        /** Successful, Failed или Unknown. */
        String buildState,

        String projectName,

        String environmentName,

        String startedAt,

        String finishedAt,

        /** «Changes by <a href="…">…</a>» — то же, чем деплой поясняет свой triggerSentence. */
        String buildReason,

        /** Ссылка на сборку в Bamboo; собрана поллером из {@code infra-manager.bamboo.base-url}. */
        String resultUrl) {

    public boolean isFailure() {
        return StringUtils.hasText(buildState) && !"Successful".equalsIgnoreCase(buildState);
    }

    public String projectNameOrUnknown() {
        return StringUtils.hasText(projectName) ? projectName : "UNKNOWN";
    }

    public String environmentNameOrUnknown() {
        return StringUtils.hasText(environmentName) ? environmentName : "UNKNOWN";
    }

    public Instant startedInstant() {
        return BambooDeploymentEvent.parseInstant(startedAt);
    }

    public Instant finishedInstant() {
        return BambooDeploymentEvent.parseInstant(finishedAt);
    }

    /** Null, если один из концов отсутствует или не разбирается. */
    public Duration duration() {
        Instant from = startedInstant();
        Instant to = finishedInstant();
        if (from == null || to == null || to.isBefore(from)) {
            return null;
        }
        return Duration.between(from, to);
    }
}
