package com.flowops.infra.persistence.mapping;

import com.flowops.domain.EmailLogEntry;
import com.flowops.infra.persistence.entity.EmailLogEntryEntity;

/**
 * Maps the {@link EmailLogEntry} aggregate to and from its JPA entity.
 * Stateless — safe to call from any thread.
 *
 * <p>{@code fromEntity} runs inside the persistence adapter (i.e. inside the
 * transaction), so lazy associations are safely resolved here and the
 * resulting domain object needs no live persistence context.
 */
public final class EmailLogEntryMappers {

    private EmailLogEntryMappers() {
    }

    /** Domain → entity. Null-safe; nested aggregates are mapped recursively. */
    public static EmailLogEntryEntity toEntity(EmailLogEntry d) {
        if (d == null) {
            return null;
        }
        EmailLogEntryEntity e = new EmailLogEntryEntity();
        e.setId(d.getId());
        e.setToAddress(d.getToAddress());
        e.setSubject(d.getSubject());
        e.setBody(d.getBody());
        e.setStatus(d.getStatus());
        e.setOutboxId(d.getOutboxId());
        e.setCreatedAt(d.getCreatedAt());
        return e;
    }

    /** Entity → domain. Null-safe; nested aggregates are mapped recursively. */
    public static EmailLogEntry fromEntity(EmailLogEntryEntity e) {
        if (e == null) {
            return null;
        }
        EmailLogEntry d = new EmailLogEntry();
        d.setId(e.getId());
        d.setToAddress(e.getToAddress());
        d.setSubject(e.getSubject());
        d.setBody(e.getBody());
        d.rehydrateStatus(e.getStatus());
        d.setOutboxId(e.getOutboxId());
        d.setCreatedAt(e.getCreatedAt());
        return d;
    }
}
