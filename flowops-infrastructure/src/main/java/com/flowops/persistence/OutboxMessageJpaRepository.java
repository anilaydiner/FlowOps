package com.flowops.persistence;

import com.flowops.application.notification.outbox.OutboxStatus;
import com.flowops.persistence.entity.OutboxMessageEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxMessageJpaRepository extends JpaRepository<OutboxMessageEntity, Long> {

    Optional<OutboxMessageEntity> findByIdempotencyKey(String idempotencyKey);

    @Query(value = """
            SELECT * FROM outbox
            WHERE status IN ('PENDING', 'FAILED')
              AND (next_retry_at IS NULL OR next_retry_at <= :now)
              AND retry_count < max_retries
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxMessageEntity> claimBatch(@Param("now") Instant now, @Param("limit") int limit);

    long countByStatus(OutboxStatus status);

    @Query(value = "SELECT * FROM outbox WHERE idempotency_key LIKE :prefix || '%' ORDER BY created_at ASC, id ASC",
            nativeQuery = true)
    List<OutboxMessageEntity> findByKeyPrefix(@Param("prefix") String prefix);

}
