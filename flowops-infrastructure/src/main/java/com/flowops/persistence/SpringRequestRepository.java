package com.flowops.persistence;

import com.flowops.application.request.port.ApprovalStore;
import com.flowops.domain.request.Request;
import com.flowops.domain.execution.ExecutionPlan;
import com.flowops.domain.execution.port.ExecutionPlanRepository;
import com.flowops.domain.request.port.RequestRepository;
import com.flowops.persistence.entity.RequestEntity;
import com.flowops.persistence.mapping.RequestMappers;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** JPA adapter for the {@link RequestRepository} port. Maps domain ↔ entity. */
@Repository
public class SpringRequestRepository implements RequestRepository, ApprovalStore {

    private final RequestJpaRepository jpaRepository;
    private final JdbcTemplate jdbc;
    private final EntityManager entities;
    private final ExecutionPlanRepository plans;
    private final int lockTimeoutMillis;

    public SpringRequestRepository(RequestJpaRepository jpaRepository, JdbcTemplate jdbc,
                                   EntityManager entities, ExecutionPlanRepository plans,
                                   @Value("${flowops.request.lock-timeout-millis:5000}") int lockTimeoutMillis) {
        this.jpaRepository = jpaRepository;
        this.jdbc = jdbc;
        this.entities = entities;
        this.plans = plans;
        if (lockTimeoutMillis < 1) throw new IllegalArgumentException("Request lock timeout must be positive");
        this.lockTimeoutMillis = lockTimeoutMillis;
    }

    @Override
    public Request save(Request request) {
        RequestEntity saved = jpaRepository.saveAndFlush(RequestMappers.toEntity(request));
        backfill(request, saved);
        return request;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void saveApproval(Request request, ExecutionPlan plan) {
        if (!plan.id().equals(request.getExecutionPlanId()) || !plan.requestId().equals(request.getId())) {
            throw new IllegalArgumentException("Approval and execution plan must belong to the same request");
        }
        // The manifest insert requires an approved parent, then the parent can reference the manifest.
        RequestEntity pendingLink = RequestMappers.toEntity(request);
        pendingLink.setExecutionPlanId(null);
        pendingLink.setExecutionPlannedAt(null);
        backfill(request, jpaRepository.saveAndFlush(pendingLink));
        plans.save(plan);
        save(request);
    }

    @Override
    public Optional<Request> findById(Long id) {
        return jpaRepository.findById(id).map(RequestMappers::fromEntity);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Request> findByIdForUpdate(Long id) {
        // JDBC avoids Hibernate auto-flushing child writes before the parent lock.
        jdbc.queryForObject("SELECT set_config('lock_timeout', ?, true)", String.class, lockTimeoutMillis + "ms");
        List<Long> ids;
        try {
            ids = jdbc.queryForList("SELECT id FROM requests WHERE id=? FOR UPDATE", Long.class, id);
        } catch (UncategorizedSQLException failure) {
            // PostgreSQL lock_not_available (including lock_timeout) is not classified by
            // Spring's default SQL-state translator. Normalize it for the HTTP 409 handler.
            if ("55P03".equals(failure.getSQLException().getSQLState())) {
                throw new CannotAcquireLockException("Request write lock unavailable: " + id, failure);
            }
            throw failure;
        }
        if (ids.isEmpty()) return Optional.empty();
        RequestEntity entity = entities.find(RequestEntity.class, id);
        // A managed entity loaded before waiting may otherwise retain the previous version/status.
        entities.refresh(entity);
        return Optional.of(RequestMappers.fromEntity(entity));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Request> tryFindByIdForUpdate(Long id) {
        var ids = jdbc.queryForList("SELECT id FROM requests WHERE id=? FOR UPDATE SKIP LOCKED", Long.class, id);
        if (ids.isEmpty()) return Optional.empty();
        var entity = entities.find(RequestEntity.class, id);
        entities.refresh(entity);
        return Optional.of(RequestMappers.fromEntity(entity));
    }

    private static void backfill(Request d, RequestEntity e) {
        d.recordPersistence(e.getId(), e.getVersion());
    }
}
