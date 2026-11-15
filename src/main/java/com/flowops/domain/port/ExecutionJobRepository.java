package com.flowops.domain.port;

import com.flowops.domain.ExecutionJob;
import com.flowops.domain.JobStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for {@link ExecutionJob} records.
 */
public interface ExecutionJobRepository {

    ExecutionJob save(ExecutionJob job);

    List<ExecutionJob> saveAll(List<ExecutionJob> jobs);

    Optional<ExecutionJob> findByIdempotencyKey(String idempotencyKey);

    List<ExecutionJob> findByRequestIdOrderByCreatedAtAsc(Long requestId);

    long countByRequestIdAndStatusNot(Long requestId, JobStatus status);

    long countByRequestId(Long requestId);

    long countByStatus(JobStatus status);

    /** Claim a batch of processable jobs (FOR UPDATE SKIP LOCKED). */
    List<ExecutionJob> claimBatch(Instant now, int limit);

    List<ExecutionJob> findByRequestIdIn(List<Long> requestIds);

    void resetNextRetryAt(Long id, Instant ts);
}
