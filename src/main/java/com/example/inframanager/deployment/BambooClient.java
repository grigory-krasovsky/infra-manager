package com.example.inframanager.deployment;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;

/**
 * Тот кусок REST API Bamboo, который нужен запасному пути с опросом.
 *
 * <p>Используется только при {@code infra-manager.bamboo.source=poll}; с вебхуками мы
 * в Bamboo не ходим вообще.
 */
public interface BambooClient {

    @GetExchange("/rest/api/latest/deploy/environment/{environmentId}/results")
    EnvironmentResults environmentResults(@PathVariable long environmentId,
                                          @RequestParam("max-results") int maxResults);

    /**
     * Сборка, породившая деплой. Нужна за тем, чего сам результат деплоя не знает: за
     * связанными задачами, которые Bamboo вытаскивает из сообщений коммитов, и за самими
     * коммитами — по ним видно, каким пул-реквестом сборка была вызвана.
     *
     * @param buildKey ключ вида {@code LIZA-APIP-636}
     * @param expand   {@code jiraIssues,changes.change}; без него Bamboo разделы не отдаёт
     */
    @GetExchange("/rest/api/latest/result/{buildKey}")
    BuildResult buildResult(@PathVariable String buildKey, @RequestParam("expand") String expand);

    /**
     * Последние результаты билд-плана — независимо от того, дошла ли какая-то из сборок
     * до деплоя. Нужен там, где падение случается раньше деплоя: сборка, не дошедшая до
     * релиза, не порождает результата деплоя, и {@link #environmentResults} её не увидит.
     *
     * <p>Без {@code expand=results.result} Bamboo отдаёт только ключ и статус — ни времени
     * завершения, ни причины запуска, — так что раздел приходится запрашивать явно, как и
     * у {@link #buildResult}.
     *
     * @param planKey ключ плана вида {@code LIZA-REST}, без номера сборки
     */
    @GetExchange("/rest/api/latest/result/{planKey}")
    PlanResults planResults(@PathVariable String planKey,
                            @RequestParam("max-results") int maxResults,
                            @RequestParam("expand") String expand);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BuildResult(JiraIssues jiraIssues, Changes changes) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record JiraIssues(List<Issue> issue) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Issue(String key, String summary) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Changes(List<Change> change) {
        }

        /** Коммит сборки; {@code comment} — его сообщение целиком, со всеми переводами строк. */
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Change(String comment) {
        }

        public List<Issue> issues() {
            return jiraIssues == null || jiraIssues.issue() == null ? List.of() : jiraIssues.issue();
        }

        public List<Change> changeList() {
            return changes == null || changes.change() == null ? List.of() : changes.change();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EnvironmentResults(List<DeploymentResult> results) {
    }

    /** Тело {@code /result/{planKey}} — те же результаты сборки, что видны в интерфейсе плана. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PlanResults(Results results) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Results(List<Summary> result) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Summary(

                /** {@code LIZA-REST-3081}: план плюс номер сборки. */
                String buildResultKey,

                /** Successful, Failed или Unknown. */
                String buildState,

                /** Queued, InProgress или Finished. */
                String lifeCycleState,

                /** ISO-8601, в отличие от миллисекунд эпохи у {@link DeploymentResult}. */
                String buildStartedTime,

                String buildCompletedTime,

                /** «Changes by <a href="…">…</a>» — та же разметка, что у triggerSentence деплоя. */
                String buildReason) {

            public boolean isFinished() {
                return "Finished".equalsIgnoreCase(lifeCycleState);
            }

            public boolean isSuccessful() {
                return "Successful".equalsIgnoreCase(buildState);
            }
        }

        public List<Summary> resultList() {
            return results == null || results.result() == null ? List.of() : results.result();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DeploymentResult(

            long id,

            /** SUCCESS, FAILED или UNKNOWN. */
            String deploymentState,

            /** QUEUED, IN_PROGRESS или FINISHED. */
            String lifeCycleState,

            String deploymentVersionName,

            /** Миллисекунды эпохи. */
            Long startedDate,

            Long finishedDate,

            String reasonSummary) {

        public boolean isFinished() {
            return "FINISHED".equalsIgnoreCase(lifeCycleState);
        }
    }
}
