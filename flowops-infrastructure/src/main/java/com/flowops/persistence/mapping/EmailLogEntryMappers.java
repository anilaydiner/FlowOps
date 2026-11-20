package com.flowops.persistence.mapping;

import com.flowops.application.notification.model.EmailLogEntry;
import com.flowops.persistence.entity.EmailLogEntryEntity;

/**
 * Maps the {@link EmailLogEntry} delivery record to and from its JPA entity. Stateless —
 * safe to call from any thread.
 *
 * <p>
 * {@code fromEntity} runs inside the persistence adapter (i.e. inside the transaction),
 * so lazy associations are safely resolved here and the resulting application record
 * needs no live persistence context.
 */
public final class EmailLogEntryMappers {

    private EmailLogEntryMappers() {
    }

    /** Application record → entity. Null-safe. */
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

    /** Entity → application record. Null-safe. */
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
