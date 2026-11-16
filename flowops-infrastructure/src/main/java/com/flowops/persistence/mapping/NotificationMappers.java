package com.flowops.persistence.mapping;

import com.flowops.domain.notification.Notification;
import com.flowops.persistence.entity.NotificationEntity;

/**
 * Maps the {@link Notification} aggregate to and from its JPA entity.
 * Stateless — safe to call from any thread.
 *
 * <p>{@code fromEntity} runs inside the persistence adapter (i.e. inside the
 * transaction), so lazy associations are safely resolved here and the
 * resulting domain object needs no live persistence context.
 */
public final class NotificationMappers {

    private NotificationMappers() {
    }

    /** Domain → entity. Null-safe; nested aggregates are mapped recursively. */
    public static NotificationEntity toEntity(Notification d) {
        if (d == null) {
            return null;
        }
        NotificationEntity e = new NotificationEntity();
        e.setId(d.getId());
        e.setRecipientId(d.getRecipientId());
        e.setType(d.getType());
        e.setTitle(d.getTitle());
        e.setBody(d.getBody());
        e.setRequestId(d.getRequestId());
        e.setReadAt(d.getReadAt());
        e.setCreatedAt(d.getCreatedAt());
        return e;
    }

    /** Entity → domain. Null-safe; nested aggregates are mapped recursively. */
    public static Notification fromEntity(NotificationEntity e) {
        if (e == null) {
            return null;
        }
        Notification d = new Notification();
        d.setId(e.getId());
        d.setRecipientId(e.getRecipientId());
        d.setType(e.getType());
        d.setTitle(e.getTitle());
        d.setBody(e.getBody());
        d.setRequestId(e.getRequestId());
        d.setReadAt(e.getReadAt());
        d.setCreatedAt(e.getCreatedAt());
        return d;
    }
}
