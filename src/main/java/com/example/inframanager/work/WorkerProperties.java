package com.example.inframanager.work;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("infra-manager.workers")
public record WorkerProperties(

        /** Set false in tests so polling does not race with explicit runOnce() calls. */
        @DefaultValue("true") boolean schedulingEnabled,

        @DefaultValue Settings inbound,
        @DefaultValue Settings outbound) {

    public record Settings(

            @DefaultValue("2s") Duration pollInterval,

            /** Upper bound on rows claimed per tick; each row is still its own transaction. */
            @DefaultValue("20") int batchSize,

            @DefaultValue("8") int maxAttempts,

            @DefaultValue("5s") Duration baseBackoff,

            @DefaultValue("10m") Duration maxBackoff) {
    }
}
