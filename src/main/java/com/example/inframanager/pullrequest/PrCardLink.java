package com.example.inframanager.pullrequest;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Помнит, какая карточка Trello отражает какой пул-реквест.
 *
 * <p>Payload Bitbucket ничего не знает про Trello, поэтому без этой строки сервис умел
 * бы только создавать карточки, но не перемещать их.
 */
@Entity
@Table(name = "pr_card_link")
public class PrCardLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_key", nullable = false, length = 64)
    private String projectKey;

    @Column(name = "repo_slug", nullable = false, length = 128)
    private String repoSlug;

    @Column(name = "pr_id", nullable = false)
    private long prId;

    @Column(name = "trello_board_id", nullable = false, length = 64)
    private String trelloBoardId;

    /** Null, пока карточка реально не создана в Trello. */
    @Column(name = "trello_card_id", length = 64)
    private String trelloCardId;

    @Column(name = "current_list_id", length = 64)
    private String currentListId;

    @Column(name = "issue_key", length = 64)
    private String issueKey;

    @Column(nullable = false)
    private boolean archived;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PrCardLink() {
    }

    public PrCardLink(PullRequestRef ref, String trelloBoardId) {
        this.projectKey = ref.projectKey();
        this.repoSlug = ref.repoSlug();
        this.prId = ref.prId();
        this.trelloBoardId = trelloBoardId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getProjectKey() {
        return projectKey;
    }

    public String getRepoSlug() {
        return repoSlug;
    }

    public long getPrId() {
        return prId;
    }

    public String getTrelloBoardId() {
        return trelloBoardId;
    }

    public String getTrelloCardId() {
        return trelloCardId;
    }

    public String getCurrentListId() {
        return currentListId;
    }

    public String getIssueKey() {
        return issueKey;
    }

    public void setIssueKey(String issueKey) {
        this.issueKey = issueKey;
        touch();
    }

    public boolean isArchived() {
        return archived;
    }

    public void recordCard(String cardId, String listId, boolean archived) {
        this.trelloCardId = cardId;
        this.currentListId = listId;
        this.archived = archived;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
