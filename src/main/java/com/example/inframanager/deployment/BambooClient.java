package com.example.inframanager.deployment;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;

/**
 * The slice of Bamboo's REST API the polling fallback needs.
 *
 * <p>Only used when {@code infra-manager.bamboo.source=poll}; with webhooks we never
 * call Bamboo at all.
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

            /** SUCCESS, FAILED or UNKNOWN. */
            String deploymentState,

            /** QUEUED, IN_PROGRESS or FINISHED. */
            String lifeCycleState,

            String deploymentVersionName,

            /** Epoch milliseconds. */
            Long startedDate,

            Long finishedDate,

            String reasonSummary) {

        public boolean isFinished() {
            return "FINISHED".equalsIgnoreCase(lifeCycleState);
        }
    }
}
