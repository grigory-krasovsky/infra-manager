package com.example.inframanager.deployment;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("infra-manager.bamboo")
public record BambooProperties(

        /**
         * Defaults to NONE so the service starts before Bamboo is wired up. Flip to
         * WEBHOOK once the webhook template exists, or POLL if this Bamboo has no
         * Communication -> Webhook templates section.
         */
        @DefaultValue("none") Source source,

        /** As reachable from inside the container; see docs/runbook.md. */
        @DefaultValue("") String baseUrl,

        @DefaultValue("") String token,

        /** Shared secret the webhook template sends back to us. */
        @DefaultValue("") String webhookSecret,

        @DefaultValue("5s") Duration connectTimeout,

        @DefaultValue("15s") Duration readTimeout,

        /**
         * Deployment project id to display name. Only needed when the Bamboo webhook
         * template cannot render a project name -- older templates expose just the id.
         */
        @DefaultValue Map<Long, String> projectNames,

        @DefaultValue Poll poll) {

    public enum Source {

        /** Bamboo pushes to us. Preferred: no polling load, near-instant. */
        WEBHOOK,

        /** We ask Bamboo. Fallback for versions without webhook templates. */
        POLL,

        /** Bamboo not wired up yet. */
        NONE
    }

    public record Poll(

            @DefaultValue("60s") Duration interval,

            /** How far back each pass looks; only unseen results produce anything. */
            @DefaultValue("10") int maxResults,

            @DefaultValue List<Environment> environments) {

        /**
         * Environment ids have to be listed explicitly. Discovering them from the
         * deployment dashboard would be one more Bamboo response shape to depend on,
         * and the names are needed for the message anyway.
         */
        public record Environment(long id, String projectName, String environmentName) {
        }
    }
}
