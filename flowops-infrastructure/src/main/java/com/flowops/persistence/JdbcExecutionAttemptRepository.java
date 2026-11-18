package com.flowops.persistence;

import com.flowops.application.execution.model.ExecutionAttempt;
import com.flowops.application.execution.model.ExecutionAttempt.*;
import com.flowops.application.execution.port.ExecutionAttemptRepository;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcExecutionAttemptRepository implements ExecutionAttemptRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper=new ObjectMapper();
    public JdbcExecutionAttemptRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void append(ExecutionAttempt a) {
        jdbc.update("""
                INSERT INTO execution_attempts(claim_token,request_id,job_id,attempt_number,idempotency_key,action,input_payload,
                    correlation_id,authorized_at,initial_lease_expires_at,deadline)
                VALUES (?,?,?,?,?,?,?::jsonb,?,?,?,?)
                """,a.token(),a.requestId(),a.jobId(),a.number(),a.idempotencyKey(),a.action(),mapper.writeValueAsString(a.input()),
                a.correlationId(),ts(a.authorizedAt()),ts(a.initialLeaseExpiresAt()),ts(a.deadline()));
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void append(Event e) {
        jdbc.update("""
                INSERT INTO execution_attempt_events(event_id,request_id,job_id,claim_token,baseline_job_id,kind,occurred_at,
                    lease_expires_at,outcome,details,recovery_command_id) VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?)
                """,e.id(),e.requestId(),e.jobId(),e.attemptToken(),e.baselineJobId(),e.kind().name(),ts(e.at()),
                ts(e.leaseExpiresAt()),e.outcome(),mapper.writeValueAsString(e.details()),e.recoveryCommandId());
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void observeBaseline(Baseline b) {
        jdbc.update("""
                INSERT INTO execution_attempt_baselines(job_id,request_id,observed_at,status,attempt_count,first_started_at,
                    start_inferred,claim_token,observed_lease_expires_at) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(job_id) DO NOTHING
                """,b.jobId(),b.requestId(),ts(b.observedAt()),b.status(),b.attemptCount(),ts(b.firstStartedAt()),
                b.startInferred(),b.claimToken(),ts(b.observedLeaseExpiresAt()));
    }
    @Override public Optional<ExecutionAttempt> find(UUID token) {
        if(token==null) return Optional.empty();
        return jdbc.query("SELECT * FROM execution_attempts WHERE claim_token=?",this::attempt,token).stream().findFirst();
    }
    @Override public Optional<Baseline> baseline(Long jobId) {
        return jdbc.query("SELECT * FROM execution_attempt_baselines WHERE job_id=?",(rs,n)->new Baseline(rs.getLong("job_id"),
                rs.getLong("request_id"),instant(rs,"observed_at"),rs.getString("status"),rs.getInt("attempt_count"),
                instant(rs,"first_started_at"),rs.getBoolean("start_inferred"),rs.getObject("claim_token",UUID.class),
                instant(rs,"observed_lease_expires_at")),jobId).stream().findFirst();
    }
    @Override public List<ExecutionAttempt> attempts(Long requestId,Long jobId,Integer before,int limit) {
        return jdbc.query("SELECT * FROM execution_attempts WHERE request_id=? AND job_id=? AND attempt_number<? ORDER BY attempt_number DESC LIMIT ?",
                this::attempt,requestId,jobId,before==null?Integer.MAX_VALUE:before,limit);
    }
    @Override public List<Event> events(Long requestId,Long jobId,Long before,int limit) {
        return jdbc.query("SELECT e.*,a.attempt_number FROM execution_attempt_events e LEFT JOIN execution_attempts a ON a.claim_token=e.claim_token WHERE e.request_id=? AND e.job_id=? AND e.sequence_id<? ORDER BY e.sequence_id DESC LIMIT ?",
                (rs,n)->new Event(rs.getObject("event_id",UUID.class),rs.getLong("sequence_id"),rs.getLong("request_id"),rs.getLong("job_id"),
                        rs.getObject("claim_token",UUID.class),rs.getObject("attempt_number",Integer.class),rs.getObject("baseline_job_id",Long.class),Kind.valueOf(rs.getString("kind")),
                        instant(rs,"occurred_at"),instant(rs,"lease_expires_at"),rs.getString("outcome"),json(rs.getString("details")),
                        rs.getObject("recovery_command_id",UUID.class)),requestId,jobId,before==null?Long.MAX_VALUE:before,limit);
    }
    private ExecutionAttempt attempt(ResultSet rs,int n) throws SQLException {
        return new ExecutionAttempt(rs.getObject("claim_token",UUID.class),rs.getLong("request_id"),rs.getLong("job_id"),rs.getInt("attempt_number"),
                rs.getString("idempotency_key"),rs.getString("action"),json(rs.getString("input_payload")),rs.getString("correlation_id"),
                instant(rs,"authorized_at"),instant(rs,"initial_lease_expires_at"),instant(rs,"deadline"));
    }
    private Map<String,Object> json(String json) { return mapper.readValue(json,new TypeReference<>(){}); }
    private static Timestamp ts(Instant value) { return value==null?null:Timestamp.from(value); }
    private static Instant instant(ResultSet rs,String name) throws SQLException { var t=rs.getTimestamp(name);return t==null?null:t.toInstant(); }
}
