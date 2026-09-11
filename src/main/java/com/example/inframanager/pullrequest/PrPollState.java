package com.example.inframanager.pullrequest;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Как выглядел пул-реквест, когда мы опрашивали его в прошлый раз.
 *
 * <p>Нужно только пути с поллингом. Вебхук сообщает, что произошло; опрос же показывает
 * только настоящее, поэтому событие приходится восстанавливать сравнением с этой строкой.
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

    /**
     * Незакрытые задачи. Отдельно от выжимки по ревьюерам, потому что меняются
     * независимо от неё: задачу можно открыть и закрыть, не трогая ни статус ревью,
     * ни версию пул-реквеста.
     */
    @Column(name = "open_tasks")
    private Integer openTasks;

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

    /** Строки, заведённые до появления колонки, ничего о задачах не знают — считаем, что их не было. */
    public int getOpenTasks() {
        return openTasks == null ? 0 : openTasks;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    /** Записывает только что сделанное наблюдение. Выжимка обрезается под размер колонки. */
    public void observe(String state, Integer version, String latestCommit, String reviewerDigest, int openTasks) {
        this.state = state;
        this.version = version;
        this.latestCommit = latestCommit;
        this.reviewerDigest = reviewerDigest == null || reviewerDigest.length() <= 64
                ? reviewerDigest
                : reviewerDigest.substring(0, 64);
        this.openTasks = openTasks;
        this.lastSeenAt = Instant.now();
    }
}
