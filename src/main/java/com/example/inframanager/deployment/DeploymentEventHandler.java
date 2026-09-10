package com.example.inframanager.deployment;

import com.example.inframanager.event.EventSource;
import com.example.inframanager.event.InboundEvent;
import com.example.inframanager.event.InboundEventHandler;
import com.example.inframanager.notify.TelegramNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a stored Bamboo delivery into a deployment record and, once the deployment
 * has actually finished, a Telegram announcement.
 *
 * <p>Runs inside the inbound worker's transaction, so recording the deployment,
 * marking it announced, and queueing the message either all happen or none do.
 */
@Component
public class DeploymentEventHandler implements InboundEventHandler {

    private static final Logger log = LoggerFactory.getLogger(DeploymentEventHandler.class);

    private final DeploymentRecordRepository repository;
    private final DeploymentMessageRenderer renderer;
    private final TelegramNotifier notifier;
    private final BambooProperties properties;
    private final ObjectMapper objectMapper;

    public DeploymentEventHandler(DeploymentRecordRepository repository,
                                  DeploymentMessageRenderer renderer,
                                  TelegramNotifier notifier,
                                  BambooProperties properties,
                                  ObjectMapper objectMapper) {
        this.repository = repository;
        this.renderer = renderer;
        this.notifier = notifier;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(EventSource source) {
        return source == EventSource.BAMBOO;
    }

    @Override
    public void handle(InboundEvent event) {
        BambooDeploymentEvent parsed = resolveProjectName(
                objectMapper.readValue(event.getPayload(), BambooDeploymentEvent.class));
        if (parsed.deploymentResultId() == null) {
            // Unrecoverable: retrying cannot add a field. Let it exhaust and land in
            // FAILED, where the bad payload stays visible next to the error.
            throw new IllegalArgumentException(
                    "Bamboo payload has no deploymentResultId; check the webhook template");
        }

        DeploymentRecord record = repository.findByBambooDeploymentResultId(parsed.deploymentResultId())
                .orElseGet(() -> repository.save(new DeploymentRecord(parsed)));
        record.apply(parsed);

        if (record.getNotifiedAt() != null) {
            log.debug("Deployment {} already announced", parsed.deploymentResultId());
            return;
        }
        if (!parsed.isNotifiable()) {
            log.debug("Deployment {} is {}/{}; recorded but not announced",
                    parsed.deploymentResultId(), parsed.normalisedStatus(), parsed.lifeCycleState());
            return;
        }

        notifier.notify(
                parsed.environmentNameOrUnknown(),
                "deploy:" + parsed.deploymentResultId(),
                renderer.render(parsed));
        record.markNotified();
        log.info("Announced deployment {} of {} to {} ({})",
                parsed.deploymentResultId(), record.getProjectName(),
                record.getEnvironmentName(), record.getStatus());
    }

    /**
     * Fills in the project name from configuration when the payload carries only an
     * id, which is the case for Bamboo webhook templates that do not expose the name.
     */
    private BambooDeploymentEvent resolveProjectName(BambooDeploymentEvent event) {
        if (event.hasProjectName() || event.deploymentProjectId() == null) {
            return event;
        }
        String configured = properties.projectNames().get(event.deploymentProjectId());
        return configured == null ? event : event.withProjectName(configured);
    }
}
