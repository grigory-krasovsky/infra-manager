package com.example.inframanager.work;

import java.time.Duration;
import java.time.Instant;

/**
 * Exponential backoff, deliberately without jitter: the service runs as a single
 * instance, so there is no herd to spread out, and deterministic delays keep the
 * retry tests readable.
 */
public final class RetryPolicy {

    private RetryPolicy() {
    }

    /**
     * @param attempts how many attempts have already been made (1 after the first failure)
     * @return when the next attempt becomes eligible
     */
    public static Instant nextAttemptAt(Instant now, int attempts, Duration base, Duration max) {
        int exponent = Math.max(0, attempts - 1);
        // Cap the shift before multiplying so a long-failing task cannot overflow.
        long multiplier = exponent >= 32 ? Integer.MAX_VALUE : 1L << exponent;
        Duration delay = base.multipliedBy(multiplier);
        return now.plus(delay.compareTo(max) > 0 ? max : delay);
    }
}
