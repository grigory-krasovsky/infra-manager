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
