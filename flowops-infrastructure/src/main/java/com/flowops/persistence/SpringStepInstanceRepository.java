package com.flowops.persistence;

import com.flowops.domain.request.StepInstance;
import com.flowops.domain.request.StepState;
import com.flowops.domain.request.port.StepInstanceRepository;
import com.flowops.persistence.entity.StepInstanceEntity;
import com.flowops.persistence.mapping.StepInstanceMappers;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** JPA adapter for the {@link StepInstanceRepository} port. Maps domain ↔ entity. */
@Repository
public class SpringStepInstanceRepository implements StepInstanceRepository {

    private final StepInstanceJpaRepository jpaRepository;
    private final EntityManager entities;
    private final JdbcTemplate jdbc;

    public SpringStepInstanceRepository(StepInstanceJpaRepository jpaRepository, EntityManager entities, JdbcTemplate jdbc) {
        this.jpaRepository = jpaRepository;
        this.entities = entities;
        this.jdbc = jdbc;
    }

    @Override
    public List<Long> findPendingRequestIds() {
        return jdbc.queryForList("SELECT DISTINCT request_id FROM step_instances WHERE state='PENDING' ORDER BY request_id", Long.class);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<StepInstance> findPendingForLockedRequest(Long requestId) {
        return jpaRepository.findTopByRequestIdAndStateOrderById(requestId, StepState.PENDING).flatMap(entity -> {
            entities.refresh(entity);
            return entity.getState() == StepState.PENDING ? Optional.of(StepInstanceMappers.fromEntity(entity)) : Optional.empty();
        });
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<StepInstance> findActiveForLockedRequest(Long requestId) {
        return jpaRepository.findTopByRequestIdAndStateInOrderById(requestId, List.of(StepState.PENDING, StepState.BLOCKED))
                .flatMap(entity -> {
                    entities.refresh(entity);
                    return entity.getState() == StepState.PENDING || entity.getState() == StepState.BLOCKED
                            ? Optional.of(StepInstanceMappers.fromEntity(entity)) : Optional.empty();
                });
    }

    @Override
    public List<Long> findReviewWork(long afterRequestId, int limit, Instant dueAt) {
        String sql = "SELECT s.request_id FROM step_instances s JOIN requests r ON r.id=s.request_id "
                + "WHERE r.status='IN_REVIEW' AND s.state IN ('PENDING','BLOCKED') AND s.request_id>? ";
        if (dueAt == null) return jdbc.queryForList(sql + "ORDER BY s.request_id LIMIT ?", Long.class, afterRequestId, limit);
        return jdbc.queryForList(sql + "AND (s.expires_at <= ? OR s.next_escalation_at <= ?) ORDER BY s.request_id LIMIT ?",
                Long.class, afterRequestId, java.sql.Timestamp.from(dueAt), java.sql.Timestamp.from(dueAt), limit);
    }

    @Override
    public long reviewWorkHighWatermark() {
        return jdbc.queryForObject("SELECT COALESCE(max(s.request_id),0) FROM step_instances s JOIN requests r ON r.id=s.request_id "
                + "WHERE r.status='IN_REVIEW' AND s.state IN ('PENDING','BLOCKED')", Long.class);
    }

    @Override
    public boolean existsByWorkflowId(Long workflowId) { return jpaRepository.existsByWorkflowId(workflowId); }

    @Override
    public StepInstance save(StepInstance step) {
        StepInstanceEntity saved = jpaRepository.saveAndFlush(StepInstanceMappers.toEntity(step));
        step.setId(saved.getId());
        return step;
    }

    @Override
    public List<StepInstance> findByRequestIdOrderById(Long requestId) {
        return jpaRepository.findByRequestIdOrderById(requestId).stream().map(StepInstanceMappers::fromEntity).toList();
    }

    @Override
    public Set<Long> findMaterializedDefinitionIds(Long requestId) {
        return jpaRepository.findMaterializedDefinitionIds(requestId);
    }

    @Override
    public Optional<StepInstance> findTopByRequestIdAndStateOrderById(Long requestId, StepState state) {
        return jpaRepository.findTopByRequestIdAndStateOrderById(requestId, state).map(StepInstanceMappers::fromEntity);
    }

    @Override
    public long countByRequestIdAndState(Long requestId, StepState state) {
        return jpaRepository.countByRequestIdAndState(requestId, state);
    }

    @Override
    public List<StepInstance> findByState(StepState state) {
        return jpaRepository.findByState(state).stream().map(StepInstanceMappers::fromEntity).toList();
    }

    @Override
    public List<StepInstance> findByStateWithAssociations(StepState state) {
        return jpaRepository.findByStateWithAssociations(state).stream().map(StepInstanceMappers::fromEntity).toList();
    }

}
