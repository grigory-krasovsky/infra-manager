package com.example.inframanager.pullrequest;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;

/**
 * Тот кусок REST API Bitbucket, который нужен пути с поллингом.
 *
 * <p>Используется, только когда вебхуки до нас не доходят, — а это обычная ситуация,
 * если сервис работает вне сети, где живёт Bitbucket.
 */
public interface BitbucketClient {

    @GetExchange("/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests")
    PullRequestPage pullRequests(@PathVariable String projectKey,
                                 @PathVariable String repoSlug,
                                 @RequestParam("state") String state,
                                 @RequestParam("order") String order,
                                 @RequestParam("limit") int limit);

    /**
     * Элементы имеют ту же форму, что Bitbucket кладёт в {@code pullRequest} тела
     * вебхука, — именно это позволяет поллеру отдавать их тому же обработчику.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PullRequestPage(List<BitbucketPrEvent.PullRequest> values) {
    }
}
