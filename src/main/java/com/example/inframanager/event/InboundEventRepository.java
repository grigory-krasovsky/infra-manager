package com.example.inframanager.event;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InboundEventRepository extends JpaRepository<InboundEvent, Long> {

    /**
     * Вставляет, если такая доставка ещё не записана.
     *
     * <p>{@code ON CONFLICT DO NOTHING}, а не перехват нарушения ограничения:
     * нарушение отравило бы окружающую транзакцию, а повторная доставка здесь —
     * обычное дело, а не ошибка.
     *
     * @return 1, если строка создана, 0 — если это дубликат
     */
    @Modifying
    @Query(value = """
            INSERT INTO inbound_event (source, external_id, event_type, payload)
            VALUES (:source, :externalId, :eventType, CAST(:payload AS jsonb))
            ON CONFLICT (source, external_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("source") String source,
                       @Param("externalId") String externalId,
                       @Param("eventType") String eventType,
                       @Param("payload") String payload);

    /** Кандидаты, читаются без блокировки; блокировка берётся построчно в {@link #lockClaimable}. */
    @Query(value = """
            SELECT id FROM inbound_event
             WHERE status = 'PENDING' AND next_attempt_at <= now()
             ORDER BY next_attempt_at, id
             LIMIT :batchSize
            """, nativeQuery = true)
    List<Long> findClaimableIds(@Param("batchSize") int batchSize);

    /**
     * Перепроверяет, что строку всё ещё можно взять, и блокирует её в транзакции
     * вызывающего. Пусто означает, что её уже забрал другой воркер либо она сменила
     * статус.
     */
    @Query(value = """
            SELECT * FROM inbound_event
             WHERE id = :id AND status = 'PENDING' AND next_attempt_at <= now()
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<InboundEvent> lockClaimable(@Param("id") long id);

    Optional<InboundEvent> findBySourceAndExternalId(EventSource source, String externalId);
}
