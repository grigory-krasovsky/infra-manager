package com.example.inframanager.pullrequest;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PrPollStateRepository extends JpaRepository<PrPollState, Long> {

    Optional<PrPollState> findByProjectKeyAndRepoSlugAndPrId(String projectKey, String repoSlug, long prId);

    default Optional<PrPollState> find(PullRequestRef ref) {
        return findByProjectKeyAndRepoSlugAndPrId(ref.projectKey(), ref.repoSlug(), ref.prId());
    }
}
