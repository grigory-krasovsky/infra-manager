package com.example.inframanager.deployment;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.util.StringUtils;

/**
 * Canonical shape of a deployment result, written by both ingestion paths: the
 * webhook controller stores the body produced by our own Bamboo template
 * (docs/bamboo-webhook-template.json), and the poller normalises REST responses
 * into the same shape. Everything downstream therefore sees one format.
 *
 * <p>Timestamps arrive as strings because the two sources disagree: Bamboo's REST
 * API returns epoch milliseconds while a Velocity template renders whatever the
 * date format produces. {@link #parseInstant} accepts both.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BambooDeploymentEvent(

        Long deploymentResultId,

        /** Bamboo's {@code deploymentState}: SUCCESS, FAILED, UNKNOWN. */
        String status,

        /** Bamboo's {@code lifeCycleState}: QUEUED, IN_PROGRESS, FINISHED. Absent on webhooks. */
        String lifeCycleState,

        String deploymentProjectName,

        /**
         * Present when the webhook template exposes it. Older Bamboo templates offer
         * only the id, so {@code infra-manager.bamboo.project-names} can supply the
         * display name instead.
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

    /** Webhooks fire on completion and omit the lifecycle field, so absent means finished. */
    public boolean isFinished() {
        return !StringUtils.hasText(lifeCycleState) || "FINISHED".equalsIgnoreCase(lifeCycleState);
    }

    /** Queued and in-progress deployments are recorded but not announced. */
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

    /** Named apart from the {@code startedAt} component because a record accessor cannot change type. */
    public Instant startedInstant() {
        return parseInstant(startedAt);
    }

    public Instant finishedInstant() {
        return parseInstant(finishedAt);
    }

    /** Null when either end is missing or unparseable -- the message just omits it. */
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
            // Fall through to ISO-8601.
        }
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException notIso) {
            return null;
        }
    }
}
