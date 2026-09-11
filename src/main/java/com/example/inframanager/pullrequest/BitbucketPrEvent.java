package com.example.inframanager.pullrequest;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Те части payload'а вебхука Bitbucket Data Center о пул-реквесте, которые нам нужны.
 *
 * <p>На практике null может прийти в любом поле: форма payload'а немного меняется от
 * типа события и от версии Bitbucket, а отсутствующее поле должно ухудшить карточку,
 * а не сорвать доставку.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BitbucketPrEvent(String eventKey, Actor actor, PullRequest pullRequest) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Actor(String name, String displayName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PullRequest(long id, String title, String description, String state,
                              /** Bitbucket увеличивает его при любой правке; поллинг по нему замечает изменения. */
                              Integer version,
                              Long updatedDate,
                              Ref fromRef, Ref toRef, Author author, List<Reviewer> reviewers, Links links) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ref(String id, String displayId,
                      /** Голова ветки; её смена означает, что запушили новые коммиты. */
                      String latestCommit,
                      Repository repository) {
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

    /**
     * @param lastReviewedCommit коммит, на котором ревьюер выставил свой нынешний статус.
     *        Вопреки названию это не «последнее, что он видел»: поле появляется только
     *        вместе с апрувом или «нужны правки» и потом за веткой не следует. Сравнение с
     *        головой ветки и отвечает на вопрос, о текущем ли коде вынесен вердикт.
     *        Отсутствует у того, кто статус не выставлял, и в теле вебхука.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reviewer(User user, String status, Boolean approved, String lastReviewedCommit) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Links(List<Link> self) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Link(String href) {
    }

    /**
     * Определяет пул-реквест по <em>целевому</em> репозиторию. Исходной стороной может
     * оказаться форк, и тогда карточка была бы привязана к чьему-то личному репозиторию.
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

    public String latestCommit() {
        return pullRequest == null || pullRequest.fromRef() == null ? null : pullRequest.fromRef().latestCommit();
    }

    public String state() {
        return pullRequest == null ? null : pullRequest.state();
    }

    /**
     * Компактная выжимка: кто одобрил, кто просит доработок и о каком коммите это сказано.
     *
     * <p>Хранится вместо списка ревьюеров, чтобы замечать смену статуса ревью, не держа
     * копию всех ревьюеров каждого пул-реквеста. Отсортирована, чтобы перестановка
     * списка на стороне Bitbucket не выглядела как изменение.
     *
     * <p>Коммит здесь не для красоты: повторное «нужны правки» статуса не меняет — он и
     * так {@code NEEDS_WORK}, — и без коммита второй заход ревьюера ничем не отличался бы
     * от первого. Ровно так карточка и застревала в колонке ревью, пока мяч был у автора.
     */
    public String reviewerDigest() {
        if (pullRequest == null || pullRequest.reviewers() == null) {
            return "";
        }
        return pullRequest.reviewers().stream()
                .filter(reviewer -> reviewer != null && reviewer.user() != null)
                .map(reviewer -> reviewer.user().name() + "=" + reviewer.status()
                        + (reviewer.lastReviewedCommit() == null ? "" : "@" + reviewer.lastReviewedCommit()))
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
    }

    /** True, если хотя бы один ревьюер одобрил именно нынешнюю голову ветки. */
    public boolean hasCurrentApproval() {
        return anyReviewer(reviewer -> Boolean.TRUE.equals(reviewer.approved()));
    }

    /** True, если хотя бы один ревьюер просит доработок именно по нынешней голове ветки. */
    public boolean hasCurrentChangesRequested() {
        return anyReviewer(reviewer -> "NEEDS_WORK".equalsIgnoreCase(reviewer.status()));
    }

    private boolean anyReviewer(java.util.function.Predicate<Reviewer> verdict) {
        if (pullRequest == null || pullRequest.reviewers() == null) {
            return false;
        }
        return pullRequest.reviewers().stream()
                .anyMatch(reviewer -> reviewer != null && verdict.test(reviewer) && concernsHead(reviewer));
    }

    /**
     * Вынесен ли вердикт о том коде, который в ветке сейчас.
     *
     * <p>Неизвестный коммит считается нынешним. Bitbucket не присылает его ни в теле
     * вебхука, ни у тех, кто статус не выставлял: трактовать «не знаю» как «устарело»
     * значило бы объявить устаревшими вообще все вердикты на пути с вебхуками.
     */
    private boolean concernsHead(Reviewer reviewer) {
        String head = latestCommit();
        return reviewer.lastReviewedCommit() == null || head == null
                || head.equals(reviewer.lastReviewedCommit());
    }

    public String targetBranch() {
        return pullRequest == null || pullRequest.toRef() == null ? null : pullRequest.toRef().displayId();
    }

    /** Логин в Bitbucket — менее двусмысленный способ опознать человека. */
    public String authorLogin() {
        if (pullRequest == null || pullRequest.author() == null || pullRequest.author().user() == null) {
            return null;
        }
        return pullRequest.author().user().name();
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
