package com.flowops.persistence;

import com.flowops.domain.execution.ExecutionPlan;
import com.flowops.domain.execution.port.ExecutionPlanRepository;
import com.flowops.domain.request.ApprovalEvidence;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcExecutionPlanRepository implements ExecutionPlanRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json=new ObjectMapper();
    public JdbcExecutionPlanRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public Optional<ExecutionPlan.Configuration> configuration(Long typeId){
        // Scalar JDBC reads the configuration now, even if JPA loaded the type before waiting for the request lock.
        return jdbc.query("SELECT id,code,updated_at,execution_actions::text FROM request_types WHERE id=?",(rs,n)->{
            Object value=json.readValue(rs.getString(4),Object.class);
            if(!(value instanceof java.util.List<?> values) || values.stream().anyMatch(a->!(a instanceof String)))
                throw new com.flowops.domain.exception.ConflictException("execution-plan-unknown","Execution configuration must be an explicit array of action codes");
            return new ExecutionPlan.Configuration(rs.getLong(1),rs.getString(2),rs.getTimestamp(3).toInstant(),values.stream().map(String.class::cast).toList());
        },typeId).stream().findFirst();
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void save(ExecutionPlan p){
        jdbc.update("INSERT INTO execution_plans(id,request_id,type_id,type_code,source_type_updated_at,planned_at,approval,expected_actions) VALUES (?,?,?,?,?,?,?::jsonb,?)",
                p.id(),p.requestId(),p.typeId(),p.typeCode(),ts(p.sourceTypeUpdatedAt()),ts(p.plannedAt()),json.writeValueAsString(p.approval().snapshot()),p.actions().size());
        for(var a:p.actions()) jdbc.update("INSERT INTO execution_plan_actions(plan_id,position,action,idempotency_key,input_payload,correlation_id) VALUES (?,?,?,?,?::jsonb,?)",
                p.id(),a.position(),a.action(),a.idempotencyKey(),json.writeValueAsString(a.input()),a.correlationId());
    }
    @Override public Optional<ExecutionPlan> findByRequestId(Long requestId){
        var headers=jdbc.query("SELECT * FROM execution_plans WHERE request_id=?",(rs,n)->new ExecutionPlan(rs.getObject("id",UUID.class),rs.getLong("request_id"),
                rs.getLong("type_id"),rs.getString("type_code"),instant(rs,"source_type_updated_at"),instant(rs,"planned_at"),
                ApprovalEvidence.fromSnapshot(map(rs.getString("approval"))),List.of()),requestId);
        if(headers.isEmpty())return Optional.empty();var h=headers.getFirst();
        var actions=jdbc.query("SELECT * FROM execution_plan_actions WHERE plan_id=? ORDER BY position",(rs,n)->new ExecutionPlan.Action(rs.getInt("position"),
                rs.getString("action"),rs.getString("idempotency_key"),map(rs.getString("input_payload")),rs.getString("correlation_id")),h.id());
        return Optional.of(new ExecutionPlan(h.id(),h.requestId(),h.typeId(),h.typeCode(),h.sourceTypeUpdatedAt(),h.plannedAt(),h.approval(),actions));
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void bindJob(UUID planId,String action,Long jobId){jdbc.update("INSERT INTO execution_plan_job_bindings(plan_id,action,job_id) VALUES (?,?,?)",planId,action,jobId);}
    private static final String MATCH="""
            SELECT 1 FROM execution_plan_job_bindings b JOIN execution_plan_actions a ON a.plan_id=b.plan_id AND a.action=b.action
            JOIN execution_plans p ON p.id=b.plan_id JOIN execution_jobs j ON j.id=b.job_id
            WHERE b.plan_id=? AND j.request_id=p.request_id AND j.action=a.action AND j.idempotency_key=a.idempotency_key
            AND j.input_payload=a.input_payload AND j.correlation_id IS NOT DISTINCT FROM a.correlation_id
            """;
    @Override public boolean jobMatches(UUID planId,Long jobId){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS("+MATCH+" AND j.id=?)",Boolean.class,planId,jobId));}
    @Override public boolean jobsMatch(UUID planId){return matches(planId,false);}
    @Override public boolean allCompleted(UUID planId){return matches(planId,true);}
    private boolean matches(UUID planId,boolean completed){
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM execution_plans p WHERE p.id=?
                    AND p.expected_actions=(SELECT count(*) FROM execution_plan_actions a WHERE a.plan_id=p.id)
                    AND p.expected_actions=(SELECT count(*) FROM execution_jobs j WHERE j.request_id=p.request_id)
                    AND p.expected_actions=(SELECT count(*) FROM (
                """+MATCH+(completed?" AND j.status='COMPLETED'":"")+") matched))",Boolean.class,planId,planId));
    }
    private Map<String,Object> map(String value){return json.readValue(value,new TypeReference<>(){});}
    private static Timestamp ts(java.time.Instant i){return i==null?null:Timestamp.from(i);}
    private static java.time.Instant instant(ResultSet rs,String field)throws SQLException{var value=rs.getTimestamp(field);return value==null?null:value.toInstant();}
}
