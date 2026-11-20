package com.flowops.persistence;

import com.flowops.domain.request.StepParticipant;
import com.flowops.domain.request.port.StepParticipantRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Roster assignments are append-only. Decision triggers record delegation successors
 * atomically.
 */
@Repository
public class JdbcStepParticipantRepository implements StepParticipantRepository {

    private final JdbcTemplate jdbc;

    public JdbcStepParticipantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void createInitialRoster(Long stepInstanceId, List<Long> userIds) {
        if (userIds.stream().anyMatch(java.util.Objects::isNull)
                || userIds.stream().distinct().count() != userIds.size()) {
            throw new IllegalArgumentException("Roster participants must be distinct persisted users");
        }
        jdbc.batchUpdate("INSERT INTO step_participants(id, slot_id, step_instance_id, user_id) VALUES (?, ?, ?, ?)",
                userIds.stream().map(userId -> {
                    UUID id = UUID.randomUUID();
                    return new Object[] { id, id, stepInstanceId, userId };
                }).toList());
    }

    @Override
    public List<StepParticipant> findByRequestId(Long requestId) {
        return query("SELECT p.* FROM step_participants p JOIN step_instances s ON s.id = p.step_instance_id "
                + "WHERE s.request_id = ? ORDER BY p.created_at, p.id", requestId);
    }

    @Override
    public List<StepParticipant> findByStepInstanceId(Long stepInstanceId) {
        return query("SELECT * FROM step_participants WHERE step_instance_id = ? ORDER BY created_at, id",
                stepInstanceId);
    }

    @Override
    public void recordRecovery(com.flowops.domain.request.StepRecovery recovery) {
        jdbc.update(
                "INSERT INTO step_recoveries(id, step_instance_id, block_episode, actor_id, reason, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                recovery.id(), recovery.stepInstanceId(), recovery.blockEpisode(), recovery.actorId(),
                recovery.reason(), java.sql.Timestamp.from(recovery.createdAt()));
    }

    @Override
    public void appendRecoveryAssignment(Long stepInstanceId, UUID recoveryId,
            com.flowops.domain.request.RecoveryAssignment assignment) {
        UUID id = UUID.randomUUID();
        UUID slot = assignment.predecessorId() == null ? id
                : jdbc.queryForObject("SELECT slot_id FROM step_participants WHERE id=? AND step_instance_id=?",
                        UUID.class, assignment.predecessorId(), stepInstanceId);
        jdbc.update(
                "INSERT INTO step_participants(id, slot_id, step_instance_id, user_id, predecessor_id, recovery_id) VALUES (?, ?, ?, ?, ?, ?)",
                id, slot, stepInstanceId, assignment.userId(), assignment.predecessorId(), recoveryId);
    }

    @Override
    public List<com.flowops.domain.request.StepRecovery> findRecoveriesByRequestId(Long requestId) {
        return jdbc.query("""
                SELECT r.* FROM step_recoveries r JOIN step_instances s ON s.id=r.step_instance_id
                WHERE s.request_id=? ORDER BY r.created_at,r.id
                """, (rs, row) -> new com.flowops.domain.request.StepRecovery(rs.getObject("id", UUID.class),
                rs.getLong("step_instance_id"), rs.getInt("block_episode"), rs.getObject("actor_id", Long.class),
                rs.getString("reason"), rs.getTimestamp("created_at").toInstant()), requestId);
    }

    private List<StepParticipant> query(String sql, Long id) {
        return jdbc.query(sql,
                (rs, row) -> new StepParticipant(rs.getObject("id", UUID.class), rs.getObject("slot_id", UUID.class),
                        rs.getLong("step_instance_id"), rs.getLong("user_id"),
                        rs.getObject("predecessor_id", UUID.class), rs.getObject("decision_id", Long.class),
                        rs.getTimestamp("created_at").toInstant(), rs.getObject("recovery_id", UUID.class)),
                id);
    }

}
