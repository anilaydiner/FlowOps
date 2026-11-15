package com.flowops.infra.persistence;

import com.flowops.domain.ExecutionJob;
import com.flowops.domain.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ExecutionJobJpaRepository extends JpaRepository<ExecutionJob, Long> {

    Optional<ExecutionJob> findByIdempotencyKey(String idempotencyKey);

    List<ExecutionJob> findByRequestIdOrderByCreatedAtAsc(Long requestId);

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
    List<ExecutionJob> claimBatch(@Param("now") Instant now, @Param("limit") int limit);

    List<ExecutionJob> findByRequestIdIn(List<Long> requestIds);

    @Modifying
    @Query(value = "UPDATE execution_jobs SET next_retry_at = :ts WHERE id = :id", nativeQuery = true)
    void resetNextRetryAt(@Param("id") Long id, @Param("ts") Instant ts);
}
