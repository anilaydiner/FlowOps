package com.flowops.domain.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One entry in a request's audit timeline (PLAN §8, iteration 8).
 *
 * <p>A domain value object representing a timeline entry; consumed by the
 * application service and mapped straight to the API response.</p>
 *
 * @param type      one of {@code STATUS}, {@code SUBMITTED}, {@code DECISION},
 *                  {@code JOB}, {@code SLA}
 * @param at        when the event happened (UTC)
 * @param actor     username of the human who caused the event (null for system)
 * @param summary   one-line human-readable description
 * @param details   structured payload (action, step, job status, …)
 */
public record AuditEvent(
        String type,
        Instant at,
        String actor,
        String summary,
        Map<String, Object> details
) {
    public static AuditEvent of(String type, Instant at, String actor, String summary, Map<String, Object> details) {
        return new AuditEvent(type, at, actor, summary,
                details == null ? null : new LinkedHashMap<>(details));
    }
}
