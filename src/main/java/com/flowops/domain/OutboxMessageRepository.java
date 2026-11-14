package com.flowops.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, Long> {

    Optional<OutboxMessage> findByIdempotencyKey(String idempotencyKey);

    /**
     * Claim a batch of dispatchable outbox messages using row-level locking.
     * Safe for concurrent dispatchers: SKIP LOCKED avoids double-claiming.
     *
     * @param maxRetries messages whose retryCount < maxRetries
     * @param now        only messages whose nextRetryAt is null or <= now
     * @param limit      batch size
     */
    @Query(value = """
            SELECT * FROM outbox
            WHERE status IN ('PENDING', 'FAILED')
              AND (next_retry_at IS NULL OR next_retry_at <= :now)
              AND retry_count < max_retries
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxMessage> claimBatch(@Param("now") Instant now, @Param("limit") int limit);

    long countByStatus(OutboxStatus status);

    /**
     * Audit support (iteration 8): SLA breach messages for one request.
     * Keys are system-generated ({@code req:{id}:sla:{recipient}}), so the
     * LIKE pattern never sees user input.
     */
    @Query(value = "SELECT * FROM outbox WHERE idempotency_key LIKE :prefix || '%' ORDER BY created_at ASC, id ASC",
            nativeQuery = true)
    List<OutboxMessage> findByKeyPrefix(@Param("prefix") String prefix);
}
