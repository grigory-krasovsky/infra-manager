package com.example.inframanager.jira;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("infra-manager.jira")
public record JiraProperties(

        /** Purely cosmetic: with it off, cards are titled from the pull request alone. */
        @DefaultValue("false") boolean enabled,

        @DefaultValue("") String baseUrl,

        /** Data Center personal access token, sent as a bearer token. */
        @DefaultValue("") String token,

        /**
         * Deliberately short. This call happens while the inbound worker holds a row
         * lock, and a slow Jira must not stall pull request events -- a missing
         * summary is a much smaller problem than a stuck queue.
         */
        @DefaultValue("2s") Duration connectTimeout,

        @DefaultValue("3s") Duration readTimeout,

        /** How long a fetched summary is reused before asking Jira again. */
        @DefaultValue("1h") Duration cacheTtl,

        /**
         * Matches issue keys in branch names and pull request titles. The default is
         * the standard Atlassian shape; tighten it to your project keys if branch
         * names contain other things that look like keys.
         */
        @DefaultValue("([A-Z][A-Z0-9]+-\\d+)") String issueKeyPattern) {
}
