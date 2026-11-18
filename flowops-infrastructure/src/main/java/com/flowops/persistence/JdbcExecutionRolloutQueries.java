package com.flowops.persistence;

import com.flowops.application.execution.port.ExecutionRolloutQueries;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcExecutionRolloutQueries implements ExecutionRolloutQueries {
    private final JdbcTemplate jdbc;
    private final String checks;
    public JdbcExecutionRolloutQueries(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
        try {checks=new ClassPathResource("db/checks/m3_rollout_acceptance.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);}
        catch(java.io.IOException e) {throw new IllegalStateException("Execution rollout checks are missing",e);}
    }
    @Override public Facts facts() {
        var counts=new LinkedHashMap<String,Long>();
        jdbc.query(checks,rs->{while(rs.next()) counts.put(rs.getString("code"),rs.getLong("value"));return counts;});
        var actions=new LinkedHashSet<>(jdbc.queryForList("""
                SELECT DISTINCT action FROM (
                    SELECT jsonb_array_elements_text(CASE WHEN jsonb_typeof(execution_actions)='array' THEN execution_actions ELSE '[]'::jsonb END) AS action FROM request_types
                    UNION SELECT action FROM execution_jobs WHERE status NOT IN ('CANCELLED','COMPLETED')
                ) configured ORDER BY action
                """,String.class));
        Map<String,Set<String>> providers=new LinkedHashMap<>();
        jdbc.query("""
                SELECT DISTINCT j.action,c.provider_id FROM execution_jobs j JOIN execution_attempts a ON a.job_id=j.id
                JOIN execution_attempt_provider_contracts c ON c.claim_token=a.claim_token WHERE j.status NOT IN ('CANCELLED','COMPLETED')
                UNION SELECT j.action,b.provider_id FROM execution_jobs j JOIN legacy_plan_provider_bindings b ON b.job_id=j.id
                WHERE j.status NOT IN ('CANCELLED','COMPLETED')
                """,rs->{while(rs.next()) providers.computeIfAbsent(rs.getString(1),k->new LinkedHashSet<>()).add(rs.getString(2));return providers;});
        return new Facts(Map.copyOf(counts),Set.copyOf(actions),Map.copyOf(providers));
    }
}
