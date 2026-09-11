package com.example.inframanager.pullrequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PrCardLinkRepository extends JpaRepository<PrCardLink, Long> {

    Optional<PrCardLink> findByProjectKeyAndRepoSlugAndPrId(String projectKey, String repoSlug, long prId);

    List<PrCardLink> findByArchivedFalseAndTrelloCardIdIsNotNull();

    List<PrCardLink> findByArchivedFalseAndTrelloCardIdIsNotNullAndCompletedAtIsNullAndListEnteredAtBefore(
            Instant enteredBefore);

    default Optional<PrCardLink> find(PullRequestRef ref) {
        return findByProjectKeyAndRepoSlugAndPrId(ref.projectKey(), ref.repoSlug(), ref.prId());
    }

    /** @return карточки, которые лежат в своей колонке с тех пор и всё ещё не отмечены выполненными */
    default List<PrCardLink> findSettledBefore(Instant enteredBefore) {
        return findByArchivedFalseAndTrelloCardIdIsNotNullAndCompletedAtIsNullAndListEnteredAtBefore(enteredBefore);
    }
}
