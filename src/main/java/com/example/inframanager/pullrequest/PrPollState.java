package com.example.inframanager.pullrequest;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * What a pull request looked like the last time we polled it.
 *
 * <p>Only the polling path needs this. A webhook states what happened; polling shows
 * only the present, so the event has to be recovered by comparing against this.
 */
@Entity
@Table(name = "pr_poll_state")
public class PrPollState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_key", nullable = false, length = 64)
    private String projectKey;

    @Column(name = "repo_slug", nullable = false, length = 128)
    private String repoSlug;

    @Column(name = "pr_id", nullable = false)
    private long prId;

    @Column(nullable = false, length = 32)
    private String state;

    @Column
    private Integer version;

    @Column(name = "latest_commit", length = 64)
    private String latestCommit;

    @Column(name = "reviewer_digest", length = 64)
    private String reviewerDigest;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    protected PrPollState() {
    }

    public PrPollState(PullRequestRef ref) {
        this.projectKey = ref.projectKey();
        this.repoSlug = ref.repoSlug();
        this.prId = ref.prId();
        this.firstSeenAt = Instant.now();
        this.lastSeenAt = this.firstSeenAt;
    }

    public String getState() {
        return state;
    }

    public Integer getVersion() {
        return version;
    }

    public String getLatestCommit() {
        return latestCommit;
    }

    public String getReviewerDigest() {
        return reviewerDigest;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    /** Records the observation just made. Digest is truncated to fit the column. */
    public void observe(String state, Integer version, String latestCommit, String reviewerDigest) {
        this.state = state;
        this.version = version;
        this.latestCommit = latestCommit;
        this.reviewerDigest = reviewerDigest == null || reviewerDigest.length() <= 64
                ? reviewerDigest
                : reviewerDigest.substring(0, 64);
        this.lastSeenAt = Instant.now();
    }
}
