package com.example.inframanager.pullrequest;

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
        @DefaultValue("") String webhookSecret) {

    public String effectiveBrowseUrl() {
        return StringUtils.hasText(browseUrl) ? browseUrl : baseUrl;
    }
}
