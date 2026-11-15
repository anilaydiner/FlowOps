package com.flowops.infra.persistence;

import com.flowops.domain.ExecutionJob;
import com.flowops.domain.JobStatus;
import com.flowops.domain.port.ExecutionJobRepository;
import com.flowops.infra.persistence.entity.ExecutionJobEntity;
import com.flowops.infra.persistence.mapping.ExecutionJobMappers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** JPA adapter for the {@link ExecutionJobRepository} port. Maps domain ↔ entity. */
@Repository
public class SpringExecutionJobRepository implements ExecutionJobRepository {

    private final ExecutionJobJpaRepository jpaRepository;

    public SpringExecutionJobRepository(ExecutionJobJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public ExecutionJob save(ExecutionJob job) {
        ExecutionJobEntity saved = jpaRepository.saveAndFlush(ExecutionJobMappers.toEntity(job));
        job.setId(saved.getId());
        return job;
    }

    @Override
    public Optional<ExecutionJob> saveIfAbsent(ExecutionJob job) {
        if (jpaRepository.findByIdempotencyKey(job.getIdempotencyKey()).isPresent()) {
            return Optional.empty();
        }
        try {
            ExecutionJobEntity saved = jpaRepository.saveAndFlush(ExecutionJobMappers.toEntity(job));
            job.setId(saved.getId());
            return Optional.of(job);
        } catch (DataIntegrityViolationException e) {
            // Concurrent insert of the same idempotency key — safe to skip.
            return Optional.empty();
        }
    }

    @Override
    public List<ExecutionJob> saveAll(List<ExecutionJob> jobs) {
        List<ExecutionJobEntity> saved = jpaRepository.saveAllAndFlush(
                jobs.stream().map(ExecutionJobMappers::toEntity).toList());
        for (int i = 0; i < jobs.size(); i++) {
            jobs.get(i).setId(saved.get(i).getId());
        }
        return jobs;
    }

    @Override
    public Optional<ExecutionJob> findByIdempotencyKey(String idempotencyKey) {
        return jpaRepository.findByIdempotencyKey(idempotencyKey).map(ExecutionJobMappers::fromEntity);
    }

    @Override
    public List<ExecutionJob> findByRequestIdOrderByCreatedAtAsc(Long requestId) {
        return jpaRepository.findByRequestIdOrderByCreatedAtAsc(requestId)
                .stream().map(ExecutionJobMappers::fromEntity).toList();
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
        return jpaRepository.claimBatch(now, limit).stream().map(ExecutionJobMappers::fromEntity).toList();
    }

    @Override
    public List<ExecutionJob> findByRequestIdIn(List<Long> requestIds) {
        return jpaRepository.findByRequestIdIn(requestIds).stream().map(ExecutionJobMappers::fromEntity).toList();
    }

    @Override
    public void resetNextRetryAt(Long id, Instant ts) {
        jpaRepository.resetNextRetryAt(id, ts);
    }

    @Override
    public Optional<ExecutionJob> findById(Long id) {
        return jpaRepository.findById(id).map(ExecutionJobMappers::fromEntity);
    }
}
