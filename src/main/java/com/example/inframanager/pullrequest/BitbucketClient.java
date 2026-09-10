package com.example.inframanager.pullrequest;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;

/**
 * The slice of Bitbucket's REST API the polling path needs.
 *
 * <p>Only used when webhooks cannot reach us -- which is the normal case when the
 * service runs outside the network Bitbucket lives in.
 */
public interface BitbucketClient {

    @GetExchange("/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests")
    PullRequestPage pullRequests(@PathVariable String projectKey,
                                 @PathVariable String repoSlug,
                                 @RequestParam("state") String state,
                                 @RequestParam("order") String order,
                                 @RequestParam("limit") int limit);

    /**
     * The entries are the same shape Bitbucket puts under {@code pullRequest} in a
     * webhook body, which is what lets the poller hand them to the same handler.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PullRequestPage(List<BitbucketPrEvent.PullRequest> values) {
    }
}
