package com.flowops.infra.persistence;

import com.flowops.domain.ExecutionJob;
import com.flowops.domain.JobStatus;
import com.flowops.domain.port.ExecutionJobRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class SpringExecutionJobRepository implements ExecutionJobRepository {

    private final ExecutionJobJpaRepository jpaRepository;

    public SpringExecutionJobRepository(ExecutionJobJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public ExecutionJob save(ExecutionJob job) {
        return jpaRepository.save(job);
    }

    @Override
    public List<ExecutionJob> saveAll(List<ExecutionJob> jobs) {
        return jpaRepository.saveAll(jobs);
    }

    @Override
    public Optional<ExecutionJob> findByIdempotencyKey(String idempotencyKey) {
        return jpaRepository.findByIdempotencyKey(idempotencyKey);
    }

    @Override
    public List<ExecutionJob> findByRequestIdOrderByCreatedAtAsc(Long requestId) {
        return jpaRepository.findByRequestIdOrderByCreatedAtAsc(requestId);
    }

    @Override
    public long countByRequestIdAndStatusNot(Long requestId, JobStatus status) {
        return jpaRepository.countByRequestIdAndStatusNot(requestId, status);
    }

    @Override
    public long countByRequestId(Long requestId) {
        return jpaRepository.countByRequestId(requestId);
    }

    @Override
    public long countByStatus(JobStatus status) {
        return jpaRepository.countByStatus(status);
    }

    @Override
    public List<ExecutionJob> claimBatch(Instant now, int limit) {
        return jpaRepository.claimBatch(now, limit);
    }

    @Override
    public List<ExecutionJob> findByRequestIdIn(List<Long> requestIds) {
        return jpaRepository.findByRequestIdIn(requestIds);
    }

    @Override
    public void resetNextRetryAt(Long id, Instant ts) {
        jpaRepository.resetNextRetryAt(id, ts);
    }
}
