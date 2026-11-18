package com.flowops.persistence;

import com.flowops.application.execution.model.ExecutionRecovery.*;
import com.flowops.application.execution.port.ExecutionRecoveryRepository;
import com.flowops.domain.execution.ProviderObservation;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcExecutionRecoveryRepository implements ExecutionRecoveryRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper=new ObjectMapper();
    public JdbcExecutionRecoveryRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void append(Command c) {
        int inserted=jdbc.update("""
                INSERT INTO execution_recovery_commands(command_id,request_id,job_id,kind,actor_id,actor_name,reason,
                    expected_version,created_at,expires_at,attempt_count,claim_token)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(command_id) DO NOTHING
                """,c.id(),c.requestId(),c.jobId(),c.kind().name(),c.actorId(),c.actorName(),c.reason(),c.expectedVersion(),
                Timestamp.from(c.createdAt()),Timestamp.from(c.expiresAt()),c.attemptCount(),c.claimToken());
        if(inserted!=1) throw new com.flowops.domain.exception.ConflictException("recovery-command-conflict","Recovery command id is already in use");
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void append(Observation o) {
        jdbc.update("""
                INSERT INTO execution_recovery_observations(command_id,recorded_at,disposition,provider_outcome,evidence_reference,result,failure_type)
                VALUES (?,?,?,?,?,?::jsonb,?)
                """,o.commandId(),Timestamp.from(o.recordedAt()),o.disposition().name(),o.provider().outcome().name(),
                o.provider().evidenceReference(),mapper.writeValueAsString(o.provider().result()),o.failureType());
    }
    private static final String SELECT="SELECT c.*,o.recorded_at,o.disposition,o.provider_outcome,o.evidence_reference,o.result,o.failure_type "
            +"FROM execution_recovery_commands c LEFT JOIN execution_recovery_observations o USING(command_id) ";
    @Override public Optional<Entry> find(UUID id) {
        return jdbc.query(SELECT+"WHERE c.command_id=?",this::read,id).stream().findFirst();
    }
    @Override public Optional<Entry> latest(Long jobId) {
        return jdbc.query(SELECT+"WHERE c.job_id=? ORDER BY c.sequence_id DESC LIMIT 1",this::read,jobId).stream().findFirst();
    }
    @Override public List<Entry> history(Long requestId,int limit) {
        return jdbc.query(SELECT+"WHERE c.request_id=? ORDER BY c.sequence_id DESC LIMIT ?",this::read,requestId,limit);
    }
    @Override public boolean hasProviderRetryAuthorization(Long jobId,int attemptCount) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM execution_recovery_commands c
                    JOIN execution_recovery_observations o USING(command_id)
                    WHERE c.job_id=? AND c.attempt_count=? AND c.kind='RETRY'
                    AND o.disposition='RETRY_AUTHORIZED' AND o.provider_outcome='SAFE_TO_RETRY'
                    AND c.sequence_id=(SELECT max(sequence_id) FROM execution_recovery_commands WHERE job_id=c.job_id))
                """,Boolean.class,jobId,attemptCount));
    }
    private Entry read(ResultSet rs,int n) throws SQLException {
        var c=new Command(rs.getObject("command_id",UUID.class),rs.getLong("sequence_id"),rs.getLong("request_id"),rs.getLong("job_id"),
                Kind.valueOf(rs.getString("kind")),rs.getLong("actor_id"),rs.getString("actor_name"),rs.getString("reason"),
                rs.getLong("expected_version"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("expires_at").toInstant(),
                rs.getInt("attempt_count"),rs.getObject("claim_token",UUID.class));
        if(rs.getTimestamp("recorded_at")==null) return new Entry(c,null);
        var provider=new ProviderObservation(ProviderObservation.Outcome.valueOf(rs.getString("provider_outcome")),
                rs.getString("evidence_reference"),mapper.readValue(rs.getString("result"),new TypeReference<Map<String,Object>>(){}));
        return new Entry(c,new Observation(c.id(),rs.getTimestamp("recorded_at").toInstant(),
                Disposition.valueOf(rs.getString("disposition")),provider,rs.getString("failure_type")));
    }
}
