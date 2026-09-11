package com.example.inframanager.pullrequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PrCardLinkRepository extends JpaRepository<PrCardLink, Long> {

    Optional<PrCardLink> findByProjectKeyAndRepoSlugAndPrId(String projectKey, String repoSlug, long prId);

    List<PrCardLink> findByArchivedFalseAndTrelloCardIdIsNotNull();

    List<PrCardLink> findByArchivedFalseAndTrelloCardIdIsNotNullAndCompletedAtIsNullAndClosedAtBefore(
            Instant closedBefore);

    default Optional<PrCardLink> find(PullRequestRef ref) {
        return findByProjectKeyAndRepoSlugAndPrId(ref.projectKey(), ref.repoSlug(), ref.prId());
    }

    /** @return карточки закрытых до этого момента пул-реквестов, ещё не отмеченные выполненными */
    default List<PrCardLink> findClosedBefore(Instant closedBefore) {
        return findByArchivedFalseAndTrelloCardIdIsNotNullAndCompletedAtIsNullAndClosedAtBefore(closedBefore);
    }
}
