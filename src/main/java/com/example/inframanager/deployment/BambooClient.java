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
