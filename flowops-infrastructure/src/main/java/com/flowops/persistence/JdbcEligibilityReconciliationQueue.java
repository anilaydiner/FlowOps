package com.flowops.persistence;

import com.flowops.application.request.port.EligibilityReconciliationQueue;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class JdbcEligibilityReconciliationQueue implements EligibilityReconciliationQueue {
    private final JdbcTemplate jdbc;
    public JdbcEligibilityReconciliationQueue(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    private static final String INSERT="""
            INSERT INTO eligibility_reconciliation_queue(request_id)
            SELECT s.request_id FROM step_instances s JOIN requests r ON r.id=s.request_id
            WHERE r.status='IN_REVIEW' AND s.state IN ('PENDING','BLOCKED') AND (
            """;
    private static final String UPSERT="""
            ) ORDER BY s.request_id
            ON CONFLICT(request_id) DO UPDATE SET generation=nextval('eligibility_reconciliation_generation'),
                available_at=clock_timestamp()
            """;

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void userChanged(Long id) {
        jdbc.update(INSERT+"""
                s.recovery_owner_id=? OR (s.state='BLOCKED' AND s.recovery_owner_id IS NULL) OR NOT s.roster_snapshotted OR EXISTS (
                    SELECT 1 FROM step_participants p WHERE p.step_instance_id=s.id AND p.user_id=?)
                """+UPSERT,id,id);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void roleChanged(Long id) {
        jdbc.update(INSERT+"""
                (s.state='BLOCKED' AND s.recovery_owner_id IS NULL) OR NOT s.roster_snapshotted
                OR EXISTS (SELECT 1 FROM user_roles ur WHERE ur.user_id=s.recovery_owner_id AND ur.role_id=?) OR EXISTS (
                    SELECT 1 FROM step_participants p JOIN user_roles ur ON ur.user_id=p.user_id
                    WHERE p.step_instance_id=s.id AND ur.role_id=?)
                """+UPSERT,id,id);
    }
    @Override public List<Ticket> findReady(int limit) {
        return jdbc.query("SELECT request_id,generation FROM eligibility_reconciliation_queue WHERE available_at<=clock_timestamp() ORDER BY available_at,generation LIMIT ?",
                (rs,row)->new Ticket(rs.getLong(1),rs.getLong(2)),limit);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void acknowledge(Ticket ticket) {
        jdbc.update("DELETE FROM eligibility_reconciliation_queue WHERE request_id=? AND generation=?",ticket.requestId(),ticket.generation());
    }
    @Override @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void defer(Ticket ticket) {
        jdbc.update("UPDATE eligibility_reconciliation_queue SET available_at=clock_timestamp()+interval '1 second' WHERE request_id=? AND generation=?",
                ticket.requestId(),ticket.generation());
    }
}
