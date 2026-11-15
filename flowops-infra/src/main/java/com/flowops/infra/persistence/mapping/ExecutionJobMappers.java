package com.flowops.infra.persistence.mapping;

import com.flowops.domain.ExecutionJob;
import com.flowops.infra.persistence.entity.ExecutionJobEntity;

/**
 * Maps the {@link ExecutionJob} aggregate to and from its JPA entity.
 * Stateless — safe to call from any thread.
 *
 * <p>{@code fromEntity} runs inside the persistence adapter (i.e. inside the
 * transaction), so lazy associations are safely resolved here and the
 * resulting domain object needs no live persistence context.
 */
public final class ExecutionJobMappers {

    private ExecutionJobMappers() {
    }

    /** Domain → entity. Null-safe; nested aggregates are mapped recursively. */
    public static ExecutionJobEntity toEntity(ExecutionJob d) {
        if (d == null) {
            return null;
        }
        ExecutionJobEntity e = new ExecutionJobEntity();
        e.setId(d.getId());
        e.setIdempotencyKey(d.getIdempotencyKey());
        e.setRequestId(d.getRequestId());
        e.setAction(d.getAction());
        e.setStatus(d.getStatus());
        e.setRetryCount(d.getRetryCount());
        e.setMaxRetries(d.getMaxRetries());
        e.setNextRetryAt(d.getNextRetryAt());
        e.setLastError(d.getLastError());
        e.setResult(d.getResult());
        e.setCorrelationId(d.getCorrelationId());
        e.setCreatedAt(d.getCreatedAt());
        e.setCompletedAt(d.getCompletedAt());
        return e;
    }

    /** Entity → domain. Null-safe; nested aggregates are mapped recursively. */
    public static ExecutionJob fromEntity(ExecutionJobEntity e) {
        if (e == null) {
            return null;
        }
        ExecutionJob d = new ExecutionJob();
        d.setId(e.getId());
        d.setIdempotencyKey(e.getIdempotencyKey());
        d.setRequestId(e.getRequestId());
        d.setAction(e.getAction());
        d.setStatus(e.getStatus());
        d.setRetryCount(e.getRetryCount());
        d.setMaxRetries(e.getMaxRetries());
        d.setNextRetryAt(e.getNextRetryAt());
        d.setLastError(e.getLastError());
        d.setResult(e.getResult());
        d.setCorrelationId(e.getCorrelationId());
        d.setCreatedAt(e.getCreatedAt());
        d.setCompletedAt(e.getCompletedAt());
        return d;
    }
}
