package com.example.inframanager.work;

import java.time.Duration;
import java.time.Instant;

/**
 * Экспоненциальная задержка, намеренно без разброса: сервис работает в одном
 * экземпляре, поэтому размазывать нагрузку не от кого, а детерминированные
 * интервалы делают тесты на повторы читаемыми.
 */
public final class RetryPolicy {

    private RetryPolicy() {
    }

    /**
     * @param attempts сколько попыток уже сделано (1 после первого сбоя)
     * @return момент, начиная с которого возможна следующая попытка
     */
    public static Instant nextAttemptAt(Instant now, int attempts, Duration base, Duration max) {
        int exponent = Math.max(0, attempts - 1);
        // Ограничиваем сдвиг до умножения, иначе долго падающая задача переполнит счётчик.
        long multiplier = exponent >= 32 ? Integer.MAX_VALUE : 1L << exponent;
        Duration delay = base.multipliedBy(multiplier);
        return now.plus(delay.compareTo(max) > 0 ? max : delay);
    }
}
