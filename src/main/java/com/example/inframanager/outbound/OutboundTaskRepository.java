package com.example.inframanager.outbound;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboundTaskRepository extends JpaRepository<OutboundTask, Long> {

    /**
     * Enqueues unless an identical task is already queued.
     *
     * <p>Same reasoning as on the inbound side: a duplicate is expected traffic, so
     * it is resolved in SQL rather than by catching a constraint violation that
     * would poison the transaction.
     *
     * @return 1 when a row was created, 0 when it was already queued
     */
    @Modifying
    @Query(value = """
            INSERT INTO outbound_task (target, action, dedup_key, payload)
            VALUES (:target, :action, :dedupKey, CAST(:payload AS jsonb))
            ON CONFLICT (dedup_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("target") String target,
                       @Param("action") String action,
                       @Param("dedupKey") String dedupKey,
                       @Param("payload") String payload);

    @Query(value = """
            SELECT id FROM outbound_task
             WHERE status = 'PENDING' AND next_attempt_at <= now()
             ORDER BY next_attempt_at, id
             LIMIT :batchSize
            """, nativeQuery = true)
    List<Long> findClaimableIds(@Param("batchSize") int batchSize);

    @Query(value = """
            SELECT * FROM outbound_task
             WHERE id = :id AND status = 'PENDING' AND next_attempt_at <= now()
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<OutboundTask> lockClaimable(@Param("id") long id);

    Optional<OutboundTask> findByDedupKey(String dedupKey);
}
