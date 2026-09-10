package com.example.inframanager.event;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InboundEventRepository extends JpaRepository<InboundEvent, Long> {

    /**
     * Inserts unless this delivery was already recorded.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than catching a constraint violation:
     * a violation would poison the surrounding transaction, and redelivery is a
     * normal occurrence here, not an error.
     *
     * @return 1 when a row was created, 0 when it was a duplicate
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

    /** Candidate ids, read without locking; the lock is taken per row in {@link #lockClaimable}. */
    @Query(value = """
            SELECT id FROM inbound_event
             WHERE status = 'PENDING' AND next_attempt_at <= now()
             ORDER BY next_attempt_at, id
             LIMIT :batchSize
            """, nativeQuery = true)
    List<Long> findClaimableIds(@Param("batchSize") int batchSize);

    /**
     * Re-checks the row is still claimable and locks it for the caller's transaction.
     * Empty means another worker got there first, or the row already moved on.
     */
    @Query(value = """
            SELECT * FROM inbound_event
             WHERE id = :id AND status = 'PENDING' AND next_attempt_at <= now()
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<InboundEvent> lockClaimable(@Param("id") long id);

    Optional<InboundEvent> findBySourceAndExternalId(EventSource source, String externalId);
}
