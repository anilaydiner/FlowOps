package com.flowops.domain.execution.port;

import com.flowops.domain.execution.ExecutionJob;
import com.flowops.domain.execution.JobStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for {@link ExecutionJob} records.
 */
public interface ExecutionJobRepository {

    /** Default number of rows claimed per worker tick. */
    int DEFAULT_BATCH_SIZE = 50;

    ExecutionJob save(ExecutionJob job);

    /** Insert only, preserving a recorded original identity. Never overwrites an existing row. */
    boolean restoreUnstarted(ExecutionJob job);

    /**
     * Save unless a job with the same idempotency key already exists.
     * Returns the saved job, or empty when the write was skipped
     * (idempotent duplicate, including concurrent inserts).
     */
    Optional<ExecutionJob> saveIfAbsent(ExecutionJob job);

    List<ExecutionJob> saveAll(List<ExecutionJob> jobs);

    Optional<ExecutionJob> findByIdempotencyKey(String idempotencyKey);

    List<ExecutionJob> findByRequestIdOrderByCreatedAtAsc(Long requestId);

    long countByRequestIdAndStatusNot(Long requestId, JobStatus status);

    long countByRequestId(Long requestId);

    long countByStatus(JobStatus status);

    record Candidate(Long jobId,Long requestId) {}
    /** Discovery only, never authorization for an external action. */
    List<Candidate> findCandidates(Instant now,int limit);
    Optional<ExecutionJob> findForLockedRequest(Long jobId,Long requestId);
    List<ExecutionJob> findAllForLockedRequest(Long requestId);

    List<ExecutionJob> findByRequestIdIn(List<Long> requestIds);

    Optional<ExecutionJob> findById(Long id);
}
