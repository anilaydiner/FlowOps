package com.flowops.persistence;

import com.flowops.domain.execution.ExecutionJob;
import com.flowops.domain.execution.JobStatus;
import com.flowops.domain.execution.port.ExecutionJobRepository;
import com.flowops.persistence.entity.ExecutionJobEntity;
import com.flowops.persistence.mapping.ExecutionJobMappers;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** JPA adapter for the {@link ExecutionJobRepository} port. Maps domain ↔ entity. */
@Repository
public class SpringExecutionJobRepository implements ExecutionJobRepository {

    private final ExecutionJobJpaRepository jpaRepository;
    private final JdbcTemplate jdbc;
    private final jakarta.persistence.EntityManager entities;
    private final tools.jackson.databind.ObjectMapper json;

    public SpringExecutionJobRepository(ExecutionJobJpaRepository jpaRepository, JdbcTemplate jdbc, jakarta.persistence.EntityManager entities, tools.jackson.databind.ObjectMapper json) {
        this.jpaRepository = jpaRepository;
        this.jdbc = jdbc;
        this.entities=entities;this.json=json;
    }

    @Override
    public ExecutionJob save(ExecutionJob job) {
        ExecutionJobEntity saved = jpaRepository.saveAndFlush(ExecutionJobMappers.toEntity(job));
        job.setId(saved.getId());
        return job;
    }

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public boolean restoreUnstarted(ExecutionJob job) {
        if(job.getId()==null || job.getStatus()!=JobStatus.PLANNED || job.getStartedAt()!=null || job.getAttemptCount()!=0 || job.getRetryCount()!=0)
            throw new IllegalArgumentException("Only recorded never-started jobs may be inserted by recovery");
        return !jdbc.queryForList("""
                INSERT INTO execution_jobs(id,idempotency_key,request_id,action,correlation_id,input_payload,created_at,max_retries)
                OVERRIDING SYSTEM VALUE VALUES (?,?,?,?,?,?::jsonb,?,?) ON CONFLICT DO NOTHING RETURNING id
                """,Long.class,job.getId(),job.getIdempotencyKey(),job.getRequestId(),job.getAction(),job.getCorrelationId(),
                json.writeValueAsString(job.getInputPayload()),java.sql.Timestamp.from(job.getCreatedAt()),job.getMaxRetries()).isEmpty();
    }

    @Override
    public Optional<ExecutionJob> saveIfAbsent(ExecutionJob job) {
        String input=job.getInputPayload()==null?null:json.writeValueAsString(job.getInputPayload());
        var inserted=jdbc.query("""
                INSERT INTO execution_jobs(idempotency_key,request_id,action,correlation_id,input_payload)
                VALUES (?,?,?,?,?::jsonb) ON CONFLICT(idempotency_key) DO NOTHING RETURNING id,created_at
                """,(rs,row)->{
                    job.setId(rs.getLong("id"));job.setCreatedAt(rs.getTimestamp("created_at").toInstant());return job;
                },job.getIdempotencyKey(),job.getRequestId(),job.getAction(),job.getCorrelationId(),input);
        return inserted.stream().findFirst();
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
    public List<Candidate> findCandidates(Instant now,int limit) {
        return jdbc.query("""
                SELECT j.id,j.request_id FROM execution_jobs j JOIN requests r ON r.id=j.request_id
                WHERE r.status='APPROVED' AND (NOT r.execution_start_unknown OR r.legacy_plan_disposition_id IS NOT NULL) AND r.execution_planned_at IS NOT NULL AND r.execution_plan_id IS NOT NULL
                AND EXISTS(SELECT 1 FROM execution_plan_job_bindings b WHERE b.plan_id=r.execution_plan_id AND b.job_id=j.id)
                AND ((j.status IN ('PLANNED','FAILED') AND j.retry_count<j.max_retries
                      AND (j.next_retry_at IS NULL OR j.next_retry_at<=?) AND j.input_payload IS NOT NULL)
                     OR (j.status='STARTED' AND j.lease_expires_at<=?))
                ORDER BY j.created_at,j.id LIMIT ?
                """,(rs,row)->new Candidate(rs.getLong(1),rs.getLong(2)),java.sql.Timestamp.from(now),java.sql.Timestamp.from(now),limit);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Optional<ExecutionJob> findForLockedRequest(Long id,Long requestId) {
        var ids=jdbc.queryForList("SELECT id FROM execution_jobs WHERE id=? AND request_id=? FOR UPDATE",Long.class,id,requestId);
        return ids.isEmpty()?Optional.empty():Optional.of(refresh(ids.getFirst()));
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public List<ExecutionJob> findAllForLockedRequest(Long requestId) {
        return jdbc.queryForList("SELECT id FROM execution_jobs WHERE request_id=? ORDER BY id FOR UPDATE",Long.class,requestId)
                .stream().map(this::refresh).toList();
    }
    private ExecutionJob refresh(Long id) {
        var entity=entities.find(ExecutionJobEntity.class,id);entities.refresh(entity);return ExecutionJobMappers.fromEntity(entity);
    }

    @Override
    public List<ExecutionJob> findByRequestIdIn(List<Long> requestIds) {
        return jpaRepository.findByRequestIdIn(requestIds).stream().map(ExecutionJobMappers::fromEntity).toList();
    }

    @Override
    public Optional<ExecutionJob> findById(Long id) {
        return jpaRepository.findById(id).map(ExecutionJobMappers::fromEntity);
    }
}
