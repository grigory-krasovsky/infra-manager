package com.example.inframanager.deployment;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One deployment of one version to one stand, as reported by Bamboo.
 *
 * <p>A deployment can be observed more than once (queued, then finished), so the
 * row is updated in place, keyed on Bamboo's result id. {@link #notifiedAt} is what
 * guarantees exactly one announcement regardless of how many observations arrive.
 */
@Entity
@Table(name = "deployment_record")
public class DeploymentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bamboo_deployment_result_id", nullable = false)
    private long bambooDeploymentResultId;

    @Column(name = "project_name", nullable = false, length = 255)
    private String projectName;

    @Column(name = "environment_name", nullable = false, length = 255)
    private String environmentName;

    @Column(name = "version_name", length = 255)
    private String versionName;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "trigger_sentence")
    private String triggerSentence;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "notified_at")
    private Instant notifiedAt;

    protected DeploymentRecord() {
    }

    public DeploymentRecord(BambooDeploymentEvent event) {
        this.bambooDeploymentResultId = event.deploymentResultId();
        this.recordedAt = Instant.now();
        apply(event);
    }

    /** Folds a newer observation of the same deployment into this row. */
    public void apply(BambooDeploymentEvent event) {
        this.projectName = event.projectNameOrUnknown();
        this.environmentName = event.environmentNameOrUnknown();
        this.versionName = event.deploymentVersionName();
        this.status = event.normalisedStatus();
        this.startedAt = event.startedInstant();
        this.finishedAt = event.finishedInstant();
        this.triggerSentence = event.triggerSentence();
    }

    public Long getId() {
        return id;
    }

    public long getBambooDeploymentResultId() {
        return bambooDeploymentResultId;
    }

    public String getProjectName() {
        return projectName;
    }

    public String getEnvironmentName() {
        return environmentName;
    }

    public String getVersionName() {
        return versionName;
    }

    public String getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getTriggerSentence() {
        return triggerSentence;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public Instant getNotifiedAt() {
        return notifiedAt;
    }

    public void markNotified() {
        this.notifiedAt = Instant.now();
    }
}
