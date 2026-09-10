package com.example.inframanager.trello;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("infra-manager.trello")
public record TrelloProperties(

        /** While false, card tasks are queued and marked SKIPPED rather than dropped. */
        @DefaultValue("false") boolean enabled,

        @DefaultValue("https://api.trello.com") String baseUrl,

        /**
         * From the API Key tab of a Power-Up at https://trello.com/apps/admin -- since
         * 2024 there is no other way to get one. Not a secret.
         */
        @DefaultValue("") String key,

        /** Grants access to the authorising account's boards. This one is a secret. */
        @DefaultValue("") String token,

        @DefaultValue("5s") Duration connectTimeout,

        @DefaultValue("10s") Duration readTimeout,

        /** How long a board's list-name to list-id mapping is trusted before refetching. */
        @DefaultValue("10m") Duration listCacheTtl,

        /**
         * Bitbucket login to Trello username. Needed because the same person is
         * usually spelled differently in the two systems -- a Latin login against a
         * Cyrillic display name -- so no amount of fuzzy matching finds them.
         * Anyone absent here, or absent from the board, simply leaves the card
         * unassigned.
         */
        @DefaultValue Map<String, String> members,

        @DefaultValue Reconciliation reconciliation) {

    /**
     * Catches what a one-way sync cannot see: a card someone dragged by hand or
     * deleted outright. This is the only reason to read from Trello at all -- and it
     * has to be polling, because Trello webhooks need a callback URL Trello can
     * reach, which an internal host is not.
     */
    public record Reconciliation(

            @DefaultValue("false") boolean enabled,

            @DefaultValue("15m") Duration interval,

            /** Default is to report, not to fight the person who moved the card. */
            @DefaultValue("log") OnDrift onDrift,

            @DefaultValue("log") OnMissing onMissing) {

        public enum OnDrift {

            /** Report the difference and leave the card where the human put it. */
            LOG,

            /** Queue a move back to where our state says the card belongs. */
            RESTORE
        }

        public enum OnMissing {

            LOG,

            /** Forget the card id, so the next event for that PR creates a fresh card. */
            FORGET
        }
    }
}
