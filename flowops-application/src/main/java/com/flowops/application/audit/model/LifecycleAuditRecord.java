package com.flowops.application.audit.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Stored facts; no lookup of mutable current state is needed to render this event. */
public record LifecycleAuditRecord(UUID id, Long sequence, String key, Long requestId, String type, String action,
                                   Instant at, Long actorId, String actor, String actorKind, String correlationId,
                                   String remoteAddress, String userAgent, String reason, String summary,
                                   Map<String, Object> before, Map<String, Object> after, Map<String, Object> details,
                                   String provenance) {
    public LifecycleAuditRecord {
        before = before == null ? Map.of() : Map.copyOf(before);
        after = after == null ? Map.of() : Map.copyOf(after);
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
