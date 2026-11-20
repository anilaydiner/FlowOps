package com.flowops.persistence;

import com.flowops.application.audit.model.LifecycleAuditRecord;
import com.flowops.application.audit.port.LifecycleAuditRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcLifecycleAuditRepository implements LifecycleAuditRepository {

    private final JdbcTemplate jdbc;

    private final ObjectMapper mapper = new ObjectMapper();

    public JdbcLifecycleAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean beginCoverage(Long requestId, Instant at, boolean legacyPrefix) {
        return jdbc.update("INSERT INTO request_audit_coverage(request_id, started_at, legacy_prefix) VALUES (?, ?, ?) "
                + "ON CONFLICT (request_id) DO NOTHING", requestId, Timestamp.from(at), legacyPrefix) == 1;
    }

    @Override
    public boolean hasCoverage(Long requestId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM request_audit_coverage WHERE request_id=?)", Boolean.class, requestId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean appendIfAbsent(LifecycleAuditRecord e) {
        return jdbc.update("""
                INSERT INTO lifecycle_audit_events(event_id, event_key, request_id, event_type, action, occurred_at,
                    actor_id, actor_name, actor_kind, correlation_id, remote_address, user_agent, reason, summary,
                    before_data, after_data, details, provenance)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?)
                ON CONFLICT (event_key) DO NOTHING
                """, e.id(), e.key(), e.requestId(), e.type(), e.action(), Timestamp.from(e.at()), e.actorId(),
                e.actor(), e.actorKind(), e.correlationId(), e.remoteAddress(), e.userAgent(), e.reason(), e.summary(),
                mapper.writeValueAsString(e.before()), mapper.writeValueAsString(e.after()),
                mapper.writeValueAsString(e.details()), e.provenance()) == 1;
    }

    @Override
    public List<LifecycleAuditRecord> findByRequestId(Long requestId) {
        return jdbc.query("SELECT * FROM lifecycle_audit_events WHERE request_id=? ORDER BY occurred_at, sequence_id",
                (rs, n) -> new LifecycleAuditRecord(rs.getObject("event_id", UUID.class), rs.getLong("sequence_id"),
                        rs.getString("event_key"), rs.getLong("request_id"), rs.getString("event_type"),
                        rs.getString("action"), rs.getTimestamp("occurred_at").toInstant(),
                        rs.getObject("actor_id", Long.class), rs.getString("actor_name"), rs.getString("actor_kind"),
                        rs.getString("correlation_id"), rs.getString("remote_address"), rs.getString("user_agent"),
                        rs.getString("reason"), rs.getString("summary"), json(rs.getString("before_data")),
                        json(rs.getString("after_data")), json(rs.getString("details")), rs.getString("provenance")),
                requestId);
    }

    private Map<String, Object> json(String json) {
        return mapper.readValue(json, new TypeReference<>() {
        });
    }

}
