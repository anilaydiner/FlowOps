package com.flowops.persistence;

import com.flowops.application.request.model.ExecutionProgress;
import com.flowops.application.request.model.ExecutionProgress.*;
import com.flowops.application.request.port.RequestExecutionQueries;
import com.flowops.domain.request.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Repository;

/**
 * One scalar query for a visible page, with independent aggregates to avoid join
 * multiplication.
 */
@Repository
public class JpaRequestExecutionQueries implements RequestExecutionQueries {

    private final EntityManager entities;

    private final Clock clock;

    public JpaRequestExecutionQueries(EntityManager entities, Clock clock) {
        this.entities = entities;
        this.clock = clock;
    }

    private static final String SQL = """
            SELECT r.id,r.status,r.outcome,(r.execution_start_unknown AND r.legacy_plan_disposition_id IS NULL) AS execution_start_unknown,
                (p.id IS NOT NULL) AS manifest_present,p.expected_actions,
                COALESCE(r.execution_plan_id=p.id AND r.execution_planned_at=p.planned_at,FALSE) AS header_matches,
                a.actions,a.matched,a.completed_expected,a.missing,a.missing_bindings,a.invalid_bindings,a.receipt_issues,
                j.total,j.planned,j.running,j.retrying,j.completed,j.uncertain,j.dead_letter,j.cancelled,j.expired_leases,j.unexpected,
                (r.status='IN_REVIEW' AND EXISTS(SELECT 1 FROM step_instances s WHERE s.request_id=r.id AND s.state='BLOCKED')) AS review_blocked
            FROM requests r LEFT JOIN execution_plans p ON p.request_id=r.id
            LEFT JOIN LATERAL (
                SELECT count(*) AS actions,
                    count(*) FILTER(WHERE bound.id IS NOT NULL AND bound.request_id=r.id AND bound.action=a.action
                        AND bound.idempotency_key=a.idempotency_key AND bound.input_payload=a.input_payload
                        AND bound.correlation_id IS NOT DISTINCT FROM a.correlation_id) AS matched,
                    count(*) FILTER(WHERE bound.status='COMPLETED' AND bound.request_id=r.id AND bound.action=a.action
                        AND bound.idempotency_key=a.idempotency_key AND bound.input_payload=a.input_payload
                        AND bound.correlation_id IS NOT DISTINCT FROM a.correlation_id) AS completed_expected,
                    count(*) FILTER(WHERE keyed.id IS NULL OR keyed.request_id<>r.id) AS missing,
                    count(*) FILTER(WHERE b.job_id IS NULL) AS missing_bindings,
                    count(*) FILTER(WHERE b.job_id IS NOT NULL AND (bound.id IS NULL OR bound.request_id<>r.id OR bound.action<>a.action
                        OR bound.idempotency_key<>a.idempotency_key OR bound.input_payload IS DISTINCT FROM a.input_payload
                        OR bound.correlation_id IS DISTINCT FROM a.correlation_id)) AS invalid_bindings,
                    count(*) FILTER(WHERE m.plan_id IS NULL OR m.job_id IS NULL OR m.job_created_at IS NULL
                        OR (COALESCE(bound.id,keyed.id) IS NOT NULL AND
                            (m.job_id IS DISTINCT FROM COALESCE(bound.id,keyed.id) OR m.job_created_at IS DISTINCT FROM COALESCE(bound.created_at,keyed.created_at)))) AS receipt_issues
                FROM execution_plan_actions a
                LEFT JOIN execution_plan_job_bindings b ON b.plan_id=a.plan_id AND b.action=a.action
                LEFT JOIN execution_jobs bound ON bound.id=b.job_id
                LEFT JOIN execution_jobs keyed ON keyed.idempotency_key=a.idempotency_key
                LEFT JOIN execution_plan_materializations m ON m.plan_id=a.plan_id AND m.action=a.action
                WHERE a.plan_id=p.id
            ) a ON TRUE
            LEFT JOIN LATERAL (
                SELECT count(*) AS total,
                    count(*) FILTER(WHERE status='PLANNED') AS planned,
                    count(*) FILTER(WHERE status='STARTED') AS running,
                    count(*) FILTER(WHERE status='FAILED') AS retrying,
                    count(*) FILTER(WHERE status='COMPLETED') AS completed,
                    count(*) FILTER(WHERE status='RECONCILIATION_REQUIRED') AS uncertain,
                    count(*) FILTER(WHERE status='DEAD_LETTER') AS dead_letter,
                    count(*) FILTER(WHERE status='CANCELLED') AS cancelled,
                    count(*) FILTER(WHERE status='STARTED' AND lease_expires_at<=:observedAt) AS expired_leases,
                    count(*) FILTER(WHERE p.id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM execution_plan_actions a
                        WHERE a.plan_id=p.id AND a.idempotency_key=j.idempotency_key)) AS unexpected
                FROM execution_jobs j WHERE j.request_id=r.id
            ) j ON TRUE
            WHERE r.id IN (:ids)
            """;

    @Override
    public Map<Long, ExecutionProgress> findByRequestIds(Collection<Long> requestIds) {
        if (requestIds.isEmpty())
            return Map.of();
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entities.createNativeQuery(SQL, Tuple.class)
            .setParameter("ids", new ArrayList<>(new LinkedHashSet<>(requestIds)))
            .setParameter("observedAt", Timestamp.from(clock.instant()))
            .getResultList();
        var result = new LinkedHashMap<Long, ExecutionProgress>();
        for (var row : rows) {
            String outcome = row.get("outcome", String.class);
            var count = row.get("expected_actions");
            var jobs = new JobCounts(n(row, "total"), n(row, "planned"), n(row, "running"), n(row, "retrying"),
                    n(row, "completed"), n(row, "uncertain"), n(row, "dead_letter"), n(row, "cancelled"),
                    n(row, "missing"), n(row, "missing_bindings"), n(row, "invalid_bindings"), n(row, "unexpected"),
                    n(row, "expired_leases"));
            var facts = new Facts(RequestStatus.valueOf(row.get("status", String.class)),
                    outcome == null ? null : RequestOutcome.valueOf(outcome), b(row, "execution_start_unknown"),
                    b(row, "review_blocked"), b(row, "manifest_present"), b(row, "header_matches"),
                    count == null ? null : ((Number) count).intValue(), n(row, "actions"), n(row, "matched"),
                    n(row, "completed_expected"), n(row, "receipt_issues"), jobs);
            result.put(((Number) row.get("id")).longValue(), ExecutionProgress.from(facts));
        }
        return Map.copyOf(result);
    }

    private static int n(Tuple row, String column) {
        return ((Number) row.get(column)).intValue();
    }

    private static boolean b(Tuple row, String column) {
        return Boolean.TRUE.equals(row.get(column));
    }

}
