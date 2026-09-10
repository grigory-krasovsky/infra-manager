package com.example.inframanager.deployment;

import java.util.List;

import com.example.inframanager.event.EventSource;
import com.example.inframanager.event.InboundEventIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Fallback ingestion for Bamboo installations without webhook templates.
 *
 * <p>Keeps no cursor of its own: every pass re-reads the last few results per
 * environment and hands them to {@link InboundEventIngestService}, whose uniqueness
 * constraint drops the ones already seen. A cursor table would be one more thing to
 * get wrong after a restart, for no gain.
 */
public class BambooDeploymentPoller {

    private static final Logger log = LoggerFactory.getLogger(BambooDeploymentPoller.class);

    private final BambooClient client;
    private final BambooProperties properties;
    private final InboundEventIngestService ingestService;
    private final ObjectMapper objectMapper;

    public BambooDeploymentPoller(BambooClient client,
                                  BambooProperties properties,
                                  InboundEventIngestService ingestService,
                                  ObjectMapper objectMapper) {
        this.client = client;
        this.properties = properties;
        this.ingestService = ingestService;
        this.objectMapper = objectMapper;
    }

    /** @return how many previously unseen deployments this pass recorded */
    public int runOnce() {
        int ingested = 0;
        for (BambooProperties.Poll.Environment environment : properties.poll().environments()) {
            try {
                ingested += pollEnvironment(environment);
            } catch (Exception e) {
                // One unreachable environment must not stop the others.
                log.warn("Failed to poll Bamboo environment {} ({})",
                        environment.id(), environment.environmentName(), e);
            }
        }
        return ingested;
    }

    private int pollEnvironment(BambooProperties.Poll.Environment environment) {
        BambooClient.EnvironmentResults response =
                client.environmentResults(environment.id(), properties.poll().maxResults());

        List<BambooClient.DeploymentResult> results =
                response == null || response.results() == null ? List.of() : response.results();

        int ingested = 0;
        for (BambooClient.DeploymentResult result : results) {
            if (!result.isFinished()) {
                continue;
            }
            BambooDeploymentEvent event = toEvent(result, environment);
            String externalId = result.id() + ":" + event.normalisedStatus();
            if (ingestService.ingest(EventSource.BAMBOO, externalId, "deployment",
                    objectMapper.writeValueAsString(event))) {
                ingested++;
            }
        }
        return ingested;
    }

    /** Normalises into the same shape the webhook path stores, so one handler serves both. */
    private BambooDeploymentEvent toEvent(BambooClient.DeploymentResult result,
                                          BambooProperties.Poll.Environment environment) {
        return new BambooDeploymentEvent(
                result.id(),
                result.deploymentState(),
                result.lifeCycleState(),
                // Names come from config here: the results endpoint does not carry them,
                // and requiring them in config is what keeps this to one call per pass.
                environment.projectName(),
                null,
                environment.environmentName(),
                result.deploymentVersionName(),
                result.startedDate() == null ? null : String.valueOf(result.startedDate()),
                result.finishedDate() == null ? null : String.valueOf(result.finishedDate()),
                result.reasonSummary());
    }
}
