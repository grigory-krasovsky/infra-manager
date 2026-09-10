package com.example.inframanager.work;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration BASE = Duration.ofSeconds(5);
    private static final Duration MAX = Duration.ofMinutes(10);

    @Test
    void firstRetryWaitsTheBaseDelay() {
        assertThat(RetryPolicy.nextAttemptAt(NOW, 1, BASE, MAX)).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    void delayDoublesWithEachAttempt() {
        assertThat(RetryPolicy.nextAttemptAt(NOW, 2, BASE, MAX)).isEqualTo(NOW.plusSeconds(10));
        assertThat(RetryPolicy.nextAttemptAt(NOW, 3, BASE, MAX)).isEqualTo(NOW.plusSeconds(20));
        assertThat(RetryPolicy.nextAttemptAt(NOW, 4, BASE, MAX)).isEqualTo(NOW.plusSeconds(40));
    }

    @Test
    void delayIsCappedAtMax() {
        assertThat(RetryPolicy.nextAttemptAt(NOW, 20, BASE, MAX)).isEqualTo(NOW.plus(MAX));
    }

    @Test
    void hugeAttemptCountDoesNotOverflow() {
        assertThat(RetryPolicy.nextAttemptAt(NOW, Integer.MAX_VALUE, BASE, MAX)).isEqualTo(NOW.plus(MAX));
    }
}
