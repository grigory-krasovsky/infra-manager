package com.example.inframanager.work;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("infra-manager.workers")
public record WorkerProperties(

        /** В тестах ставится в false, чтобы опрос не гонялся наперегонки с явными вызовами runOnce(). */
        @DefaultValue("true") boolean schedulingEnabled,

        @DefaultValue Settings inbound,
        @DefaultValue Settings outbound) {

    public record Settings(

            @DefaultValue("2s") Duration pollInterval,

            /** Предел строк, забираемых за проход; каждая всё равно в своей транзакции. */
            @DefaultValue("20") int batchSize,

            @DefaultValue("8") int maxAttempts,

            @DefaultValue("5s") Duration baseBackoff,

            @DefaultValue("10m") Duration maxBackoff) {
    }
}
