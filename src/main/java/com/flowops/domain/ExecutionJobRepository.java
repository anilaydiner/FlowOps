package com.flowops.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ExecutionJobRepository extends JpaRepository<ExecutionJob, Long> {

    Optional<ExecutionJob> findByIdempotencyKey(String idempotencyKey);

    List<ExecutionJob> findByRequestIdOrderByCreatedAtAsc(Long requestId);

    long countByRequestIdAndStatusNot(Long requestId, JobStatus status);

    long countByRequestId(Long requestId);

    long countByStatus(JobStatus status);

    /**
     * Claim a batch of processable execution jobs using row-level locking.
     * Safe for concurrent workers: SKIP LOCKED avoids double-claiming.
     */
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

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "UPDATE execution_jobs SET next_retry_at = :ts WHERE id = :id", nativeQuery = true)
    void resetNextRetryAt(@org.springframework.data.repository.query.Param("id") Long id,
                          @org.springframework.data.repository.query.Param("ts") java.time.Instant ts);
}
