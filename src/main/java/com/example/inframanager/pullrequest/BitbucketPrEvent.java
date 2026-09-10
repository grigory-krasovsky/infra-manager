package com.example.inframanager.pullrequest;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The parts of a Bitbucket Data Center pull request webhook payload we care about.
 *
 * <p>Everything is nullable in practice: the shape varies a little between event
 * kinds and between Bitbucket versions, and a missing field should degrade the card,
 * not fail the delivery.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BitbucketPrEvent(String eventKey, Actor actor, PullRequest pullRequest) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Actor(String name, String displayName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PullRequest(long id, String title, String description, String state,
                              Ref fromRef, Ref toRef, Author author, List<Reviewer> reviewers, Links links) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ref(String id, String displayId, Repository repository) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repository(String slug, String name, Project project) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Project(String key, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Author(User user) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(String name, String displayName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reviewer(User user, String status, Boolean approved) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Links(List<Link> self) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Link(String href) {
    }

    /**
     * Identifies the pull request by its <em>target</em> repository. The source side
     * can be a fork, which would key the card to somebody's personal repo.
     */
    public Optional<PullRequestRef> ref() {
        if (pullRequest == null || pullRequest.toRef() == null) {
            return Optional.empty();
        }
        Repository repository = pullRequest.toRef().repository();
        if (repository == null || repository.project() == null
                || repository.project().key() == null || repository.slug() == null) {
            return Optional.empty();
        }
        return Optional.of(new PullRequestRef(
                repository.project().key(), repository.slug(), pullRequest.id()));
    }

    public Optional<String> selfLink() {
        if (pullRequest == null || pullRequest.links() == null || pullRequest.links().self() == null) {
            return Optional.empty();
        }
        return pullRequest.links().self().stream()
                .filter(link -> link != null && link.href() != null)
                .map(Link::href)
                .findFirst();
    }

    public String sourceBranch() {
        return pullRequest == null || pullRequest.fromRef() == null ? null : pullRequest.fromRef().displayId();
    }

    public String targetBranch() {
        return pullRequest == null || pullRequest.toRef() == null ? null : pullRequest.toRef().displayId();
    }

    public String authorName() {
        if (pullRequest == null || pullRequest.author() == null || pullRequest.author().user() == null) {
            return null;
        }
        User user = pullRequest.author().user();
        return user.displayName() != null ? user.displayName() : user.name();
    }

    public List<String> reviewerNames() {
        if (pullRequest == null || pullRequest.reviewers() == null) {
            return List.of();
        }
        return pullRequest.reviewers().stream()
                .filter(reviewer -> reviewer != null && reviewer.user() != null)
                .map(reviewer -> {
                    User user = reviewer.user();
                    String name = user.displayName() != null ? user.displayName() : user.name();
                    return Boolean.TRUE.equals(reviewer.approved()) ? name + " ✔" : name;
                })
                .filter(name -> !name.isBlank())
                .toList();
    }
}
