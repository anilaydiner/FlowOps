package com.flowops.infra.persistence.mapping;

import com.flowops.domain.OutboxMessage;
import com.flowops.infra.persistence.entity.OutboxMessageEntity;

/**
 * Maps the {@link OutboxMessage} aggregate to and from its JPA entity.
 * Stateless — safe to call from any thread.
 *
 * <p>{@code fromEntity} runs inside the persistence adapter (i.e. inside the
 * transaction), so lazy associations are safely resolved here and the
 * resulting domain object needs no live persistence context.
 */
public final class OutboxMessageMappers {

    private OutboxMessageMappers() {
    }

    /** Domain → entity. Null-safe; nested aggregates are mapped recursively. */
    public static OutboxMessageEntity toEntity(OutboxMessage d) {
        if (d == null) {
            return null;
        }
        OutboxMessageEntity e = new OutboxMessageEntity();
        e.setId(d.getId());
        e.setIdempotencyKey(d.getIdempotencyKey());
        e.setChannel(d.getChannel());
        e.setRecipientId(d.getRecipientId());
        e.setPayload(d.getPayload());
        e.setStatus(d.getStatus());
        e.setRetryCount(d.getRetryCount());
        e.setMaxRetries(d.getMaxRetries());
        e.setNextRetryAt(d.getNextRetryAt());
        e.setLastError(d.getLastError());
        e.setCorrelationId(d.getCorrelationId());
        e.setCreatedAt(d.getCreatedAt());
        e.setSentAt(d.getSentAt());
        return e;
    }

    /** Entity → domain. Null-safe; nested aggregates are mapped recursively. */
    public static OutboxMessage fromEntity(OutboxMessageEntity e) {
        if (e == null) {
            return null;
        }
        OutboxMessage d = new OutboxMessage();
        d.setId(e.getId());
        d.setIdempotencyKey(e.getIdempotencyKey());
        d.setChannel(e.getChannel());
        d.setRecipientId(e.getRecipientId());
        d.setPayload(e.getPayload());
        d.setStatus(e.getStatus());
        d.rehydrateRetryCount(e.getRetryCount());
        d.setMaxRetries(e.getMaxRetries());
        d.rehydrateNextRetryAt(e.getNextRetryAt());
        d.setLastError(e.getLastError());
        d.setCorrelationId(e.getCorrelationId());
        d.setCreatedAt(e.getCreatedAt());
        d.setSentAt(e.getSentAt());
        return d;
    }
}
