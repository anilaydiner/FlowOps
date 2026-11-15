package com.flowops.infra.persistence;

import com.flowops.domain.OutboxMessage;
import com.flowops.domain.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OutboxMessageJpaRepository extends JpaRepository<OutboxMessage, Long> {

    Optional<OutboxMessage> findByIdempotencyKey(String idempotencyKey);

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

    @Query(value = "SELECT * FROM outbox WHERE idempotency_key LIKE :prefix || '%' ORDER BY created_at ASC, id ASC",
            nativeQuery = true)
    List<OutboxMessage> findByKeyPrefix(@Param("prefix") String prefix);
}
