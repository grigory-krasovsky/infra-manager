package com.example.inframanager.pullrequest;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PrCardLinkRepository extends JpaRepository<PrCardLink, Long> {

    Optional<PrCardLink> findByProjectKeyAndRepoSlugAndPrId(String projectKey, String repoSlug, long prId);

    List<PrCardLink> findByArchivedFalseAndTrelloCardIdIsNotNull();

    default Optional<PrCardLink> find(PullRequestRef ref) {
        return findByProjectKeyAndRepoSlugAndPrId(ref.projectKey(), ref.repoSlug(), ref.prId());
    }
}
