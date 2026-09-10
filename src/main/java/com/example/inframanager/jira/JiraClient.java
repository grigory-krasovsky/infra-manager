package com.example.inframanager.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;

/**
 * Один вызов только на чтение. Jira здесь не источник событий жизненного цикла — ими
 * служат пул-реквесты и деплои, — поэтому больше ничего не нужно.
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
