package com.flowops.persistence;

import com.flowops.application.execution.model.LegacyPlanDisposition.*;
import com.flowops.application.execution.port.LegacyPlanDispositionRepository;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcLegacyPlanDispositionRepository implements LegacyPlanDispositionRepository {

    private final JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    public JdbcLegacyPlanDispositionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean append(Entry e) {
        return !jdbc
            .queryForList(
                    """
                            INSERT INTO legacy_plan_dispositions(command_id,request_id,plan_id,actor_id,actor_name,expected_version,mode,reason,evidence_reference,recorded_at,payload)
                            VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT(command_id) DO NOTHING RETURNING command_id
                            """,
                    UUID.class, e.commandId(), e.requestId(), e.planId(), e.actorId(), e.actorName(),
                    e.expectedVersion(), e.mode().name(), e.reason(), e.evidenceReference(),
                    Timestamp.from(e.recordedAt()), json.writeValueAsString(e.payload()))
            .isEmpty();
    }

    @Override
    public Optional<Entry> find(UUID commandId) {
        return jdbc.query("SELECT * FROM legacy_plan_dispositions WHERE command_id=?", this::entry, commandId)
            .stream()
            .findFirst();
    }

    @Override
    public boolean payloadMatches(UUID id, Map<String, Object> payload) {
        return Boolean.TRUE
            .equals(jdbc.queryForObject("SELECT payload=?::jsonb FROM legacy_plan_dispositions WHERE command_id=?",
                    Boolean.class, json.writeValueAsString(payload), id));
    }

    @Override
    public List<Entry> history(Long requestId, int limit) {
        return jdbc.query(
                "SELECT * FROM legacy_plan_dispositions WHERE request_id=? ORDER BY recorded_at DESC,command_id DESC LIMIT ?",
                this::entry, requestId, limit);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void bindProvider(UUID dispositionId, Long jobId, String providerId) {
        jdbc.update("INSERT INTO legacy_plan_provider_bindings(job_id,disposition_id,provider_id) VALUES (?,?,?)",
                jobId, dispositionId, providerId);
    }

    @Override
    public boolean missingHistoricalJobs(Long requestId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                        SELECT EXISTS(SELECT 1 FROM (
                            SELECT job_id FROM legacy_execution_cutover WHERE request_id=?
                            UNION SELECT job_id FROM execution_attempts WHERE request_id=?
                            UNION SELECT job_id FROM execution_attempt_baselines WHERE request_id=?
                            UNION SELECT job_id FROM execution_attempt_events WHERE request_id=?
                            UNION SELECT job_id FROM execution_recovery_commands WHERE request_id=?
                        ) h WHERE NOT EXISTS(SELECT 1 FROM execution_jobs j WHERE j.id=h.job_id AND j.request_id=?))
                        OR EXISTS(SELECT 1 FROM lifecycle_audit_events e WHERE e.request_id=? AND e.event_type='JOB' AND e.details->>'jobId' IS NOT NULL
                            AND NOT EXISTS(SELECT 1 FROM execution_jobs j WHERE j.request_id=? AND j.id::text=e.details->>'jobId'))
                        """,
                Boolean.class, requestId, requestId, requestId, requestId, requestId, requestId, requestId, requestId));
    }

    @Override
    public boolean actionHistory(Long requestId, String action, String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                        SELECT EXISTS(SELECT 1 FROM execution_jobs WHERE idempotency_key=? OR (request_id=? AND action=?))
                        OR EXISTS(SELECT 1 FROM execution_attempts WHERE request_id=? AND (action=? OR idempotency_key=?))
                        OR EXISTS(SELECT 1 FROM lifecycle_audit_events WHERE request_id=? AND event_type='JOB' AND details->>'action'=?)
                        """,
                Boolean.class, key, requestId, action, requestId, action, key, requestId, action));
    }

    @Override
    public boolean completedEvidence(Long jobId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM execution_attempt_events WHERE job_id=? AND
                    ((kind='OUTCOME' AND outcome='COMPLETED') OR (kind='PROVIDER_OBSERVATION' AND outcome='COMPLETED')))
                OR EXISTS(SELECT 1 FROM execution_attempt_baselines WHERE job_id=? AND status='COMPLETED')
                OR EXISTS(SELECT 1 FROM legacy_execution_cutover WHERE job_id=? AND status='COMPLETED')
                """, Boolean.class, jobId, jobId, jobId));
    }

    private Entry entry(ResultSet rs, int n) throws SQLException {
        return new Entry(rs.getObject("command_id", UUID.class), rs.getLong("request_id"),
                rs.getObject("plan_id", UUID.class), rs.getLong("actor_id"), rs.getString("actor_name"),
                rs.getLong("expected_version"), Mode.valueOf(rs.getString("mode")), rs.getString("reason"),
                rs.getString("evidence_reference"), rs.getTimestamp("recorded_at").toInstant(),
                json.readValue(rs.getString("payload"), new TypeReference<>() {
                }));
    }

    @Override
    public boolean providerHistoryMatches(Long jobId, String providerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                        SELECT NOT EXISTS(SELECT 1 FROM execution_attempts a JOIN execution_attempt_provider_contracts c USING(claim_token) WHERE a.job_id=? AND c.provider_id<>?)
                            AND NOT EXISTS(SELECT 1 FROM execution_recovery_commands c JOIN execution_recovery_provider_contracts p USING(command_id) WHERE c.job_id=? AND p.provider_id<>?)
                        """,
                Boolean.class, jobId, providerId, jobId, providerId));
    }

}
