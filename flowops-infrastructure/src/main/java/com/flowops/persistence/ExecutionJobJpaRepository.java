package com.flowops.persistence;

import com.flowops.domain.execution.JobStatus;
import com.flowops.persistence.entity.ExecutionJobEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExecutionJobJpaRepository extends JpaRepository<ExecutionJobEntity, Long> {

    Optional<ExecutionJobEntity> findByIdempotencyKey(String idempotencyKey);

    List<ExecutionJobEntity> findByRequestIdOrderByCreatedAtAsc(Long requestId);

    long countByRequestIdAndStatusNot(Long requestId, JobStatus status);

    long countByRequestId(Long requestId);

    long countByStatus(JobStatus status);

    @Query(value = """
            SELECT * FROM execution_jobs
            WHERE status IN ('PLANNED', 'FAILED')
              AND (next_retry_at IS NULL OR next_retry_at <= :now)
              AND retry_count < max_retries
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ExecutionJobEntity> claimBatch(@Param("now") Instant now, @Param("limit") int limit);

    List<ExecutionJobEntity> findByRequestIdIn(List<Long> requestIds);

    @Modifying
    @Query(value = "UPDATE execution_jobs SET next_retry_at = :ts WHERE id = :id", nativeQuery = true)
    void resetNextRetryAt(@Param("id") Long id, @Param("ts") Instant ts);
}
