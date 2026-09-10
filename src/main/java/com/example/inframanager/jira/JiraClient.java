package com.example.inframanager.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;

/**
 * One read-only call. Jira is not a source of lifecycle events here -- pull requests
 * and deployments are -- so nothing else is needed.
 */
public interface JiraClient {

    @GetExchange("/rest/api/2/issue/{issueKey}")
    Issue issue(@PathVariable String issueKey, @RequestParam("fields") String fields);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Issue(String key, Fields fields) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Fields(String summary) {
        }

        public String summary() {
            return fields == null ? null : fields.summary();
        }
    }
}
