package com.example.inframanager.pullrequest;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

@ConfigurationProperties("infra-manager.bitbucket")
public record BitbucketProperties(

        /** Exposes /webhooks/bitbucket. Off until the webhook secret exists. */
        @DefaultValue("false") boolean enabled,

        /** As reachable from inside the container. */
        @DefaultValue("") String baseUrl,

        /**
         * The URL a human should click. Usually the same as {@link #baseUrl}, but they
         * differ when the container reaches Bitbucket through host.docker.internal.
         */
        @DefaultValue("") String browseUrl,

        @DefaultValue("") String token,

        /** Shared with the Bitbucket webhook; used for the X-Hub-Signature HMAC. */
        @DefaultValue("") String webhookSecret,

        @DefaultValue("5s") Duration connectTimeout,

        @DefaultValue("15s") Duration readTimeout,

        @DefaultValue Poll poll) {

    /**
     * Polling replaces webhooks when Bitbucket cannot open a connection to us --
     * the usual situation when this service runs outside the corporate network.
     * Repositories come from {@code infra-manager.lifecycle.repos}: polling a
     * repository we do not mirror would produce events nobody acts on.
     */
    public record Poll(

            @DefaultValue("false") boolean enabled,

            @DefaultValue("2m") Duration interval,

            /** Pull requests read per repository per pass, newest first. */
            @DefaultValue("50") int maxResults) {
    }

    public String effectiveBrowseUrl() {
        return StringUtils.hasText(browseUrl) ? browseUrl : baseUrl;
    }
}
