package com.flowops.persistence;

import com.flowops.application.execution.model.ExecutionPlanRecovery.Materialization;
import com.flowops.application.execution.port.ExecutionPlanReconciliationRepository;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcExecutionPlanReconciliationRepository implements ExecutionPlanReconciliationRepository {
    private final JdbcTemplate jdbc;
    public JdbcExecutionPlanReconciliationRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static final String ELIGIBLE="status='APPROVED' AND execution_plan_id IS NOT NULL AND (NOT execution_start_unknown OR legacy_plan_disposition_id IS NOT NULL)";
    @Override public long highWatermark(){return jdbc.queryForObject("SELECT COALESCE(max(id),0) FROM requests WHERE "+ELIGIBLE,Long.class);}
    @Override public List<Long> candidates(long after,long through,int limit){return jdbc.queryForList("SELECT id FROM requests WHERE "+ELIGIBLE+" AND id>? AND id<=? ORDER BY id LIMIT ?",Long.class,after,through,limit);}
    @Override public boolean manifestValid(UUID planId,Long requestId){
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM execution_plans p JOIN requests r ON r.id=p.request_id WHERE p.id=? AND p.request_id=?
                    AND r.execution_plan_id=p.id AND r.execution_planned_at=p.planned_at
                    AND p.expected_actions=(SELECT count(*) FROM execution_plan_actions a WHERE a.plan_id=p.id))
                """,Boolean.class,planId,requestId));
    }
    @Override public Map<String,Long> bindings(UUID planId){
        Map<String,Long> result=new LinkedHashMap<>();jdbc.query("SELECT action,job_id FROM execution_plan_job_bindings WHERE plan_id=?",rs->{while(rs.next())result.put(rs.getString(1),rs.getLong(2));return result;},planId);return result;
    }
    @Override public Map<String,Materialization> materializations(UUID planId){
        Map<String,Materialization> result=new LinkedHashMap<>();jdbc.query("SELECT * FROM execution_plan_materializations WHERE plan_id=?",rs->{
            while(rs.next()){var created=rs.getTimestamp("job_created_at");var m=new Materialization(rs.getString("action"),rs.getObject("job_id",Long.class),
                    created==null?null:created.toInstant(),rs.getObject("initial_max_retries",Integer.class),rs.getBoolean("unstarted_at_capture"),rs.getString("provenance"));result.put(m.action(),m);}return result;
        },planId);return result;
    }
    @Override public boolean actionMatches(UUID planId,String action,Long jobId){
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM execution_plan_actions a JOIN execution_plans p ON p.id=a.plan_id JOIN execution_jobs j ON j.id=?
                    WHERE a.plan_id=? AND a.action=? AND j.request_id=p.request_id AND j.action=a.action AND j.idempotency_key=a.idempotency_key
                    AND j.input_payload=a.input_payload AND j.correlation_id IS NOT DISTINCT FROM a.correlation_id)
                """,Boolean.class,jobId,planId,action));
    }
    @Override public boolean executionHistory(Long requestId,String action,String key,Long jobId){
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM execution_attempts WHERE job_id=? OR (request_id=? AND (idempotency_key=? OR action=?)))
                OR EXISTS(SELECT 1 FROM execution_attempt_events WHERE job_id=?)
                OR EXISTS(SELECT 1 FROM execution_attempt_baselines WHERE job_id=?)
                OR EXISTS(SELECT 1 FROM legacy_execution_cutover WHERE job_id=?)
                OR EXISTS(SELECT 1 FROM execution_recovery_commands WHERE job_id=?)
                OR EXISTS(SELECT 1 FROM execution_recovery_provider_contracts pc JOIN execution_recovery_commands c USING(command_id) WHERE c.request_id=? AND pc.action=?)
                OR EXISTS(SELECT 1 FROM lifecycle_audit_events WHERE request_id=? AND event_type='JOB'
                    AND (details->>'jobId'=? OR details->>'action'=?) AND action NOT IN ('PLANNED','RESTORED_FROM_PLAN'))
                """,Boolean.class,jobId,requestId,key,action,jobId,jobId,jobId,jobId,requestId,action,requestId,String.valueOf(jobId),action));
    }
    @Override public boolean anyMaterializationHistory(Long requestId,String action,String key){
        return executionHistory(requestId,action,key,null) || Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM lifecycle_audit_events WHERE request_id=? AND event_type='JOB' AND details->>'action'=?)
                    OR EXISTS(SELECT 1 FROM legacy_execution_cutover WHERE request_id=?)
                """,Boolean.class,requestId,action,requestId));
    }
    @Override public boolean completionRecorded(Long jobId){
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM execution_attempt_events WHERE job_id=? AND ((kind='OUTCOME' AND outcome='COMPLETED') OR (kind='PROVIDER_OBSERVATION' AND outcome='COMPLETED')))",Boolean.class,jobId));
    }
    @Override public boolean auditRecorded(Long requestId,String suffix){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM lifecycle_audit_events WHERE event_key=?)",Boolean.class,"request:"+requestId+":"+suffix));}
}
