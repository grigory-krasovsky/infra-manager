package com.example.inframanager.pullrequest;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PrPollStateRepository extends JpaRepository<PrPollState, Long> {

    Optional<PrPollState> findByProjectKeyAndRepoSlugAndPrId(String projectKey, String repoSlug, long prId);

    List<PrPollState> findByProjectKeyAndRepoSlugAndStateIgnoreCase(String projectKey, String repoSlug,
                                                                    String state);

    default Optional<PrPollState> find(PullRequestRef ref) {
        return findByProjectKeyAndRepoSlugAndPrId(ref.projectKey(), ref.repoSlug(), ref.prId());
    }

    /**
     * Пул-реквесты репозитория, которые в прошлый раз были открыты.
     *
     * <p>Только они и могут оказаться удалёнными — точнее, только их удаление что-то
     * значит для доски: карточка влитого или отклонённого уже лежит в конечной колонке.
     * Ограничение не косметическое: искать пропавших приходится среди тех, кого нет на
     * странице, а закрытые с неё уходят пачками просто от возраста.
     */
    default List<PrPollState> findOpen(String projectKey, String repoSlug) {
        return findByProjectKeyAndRepoSlugAndStateIgnoreCase(projectKey, repoSlug, PrPollState.OPEN);
    }
}
