package com.example.inframanager.outbound;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboundTaskRepository extends JpaRepository<OutboundTask, Long> {

    /**
     * Ставит в очередь, если такой же задачи там ещё нет.
     *
     * <p>Рассуждение то же, что и на входящей стороне: дубликат — ожидаемый трафик,
     * поэтому он разрешается средствами SQL, а не перехватом нарушения ограничения,
     * которое отравило бы транзакцию.
     *
     * @return 1, если строка создана, 0 — если задача уже была в очереди
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
