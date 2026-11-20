package com.flowops;

import com.flowops.application.mediator.Mediator;
import com.flowops.application.request.command.CreateDraftCommand;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.port.UserRepository;
import com.flowops.worker.JobWorker;
import com.flowops.worker.OutboxDispatcher;
import com.flowops.worker.SlaScanner;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;

class LifecycleAuditRollbackTest extends AbstractIntegrationTest {

    @MockitoBean
    JobWorker backgroundJobs;

    @MockitoBean
    OutboxDispatcher backgroundOutbox;

    @MockitoBean
    SlaScanner backgroundSla;

    @Autowired
    Mediator mediator;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void failedAuditWriteRollsBackBusinessStateAndCoverage() {
        var alice = users.findByUsername("alice").orElseThrow();
        var caller = new CallerIdentity(alice.getId(), alice.getUsername());
        long beforeRequests = jdbc.queryForObject("SELECT count(*) FROM requests", Long.class);
        long beforeCoverage = jdbc.queryForObject("SELECT count(*) FROM request_audit_coverage", Long.class);
        long beforeEvents = jdbc.queryForObject("SELECT count(*) FROM lifecycle_audit_events", Long.class);
        String fault = "audit_fault_" + java.util.UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + fault + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "RAISE EXCEPTION 'injected audit storage failure' USING ERRCODE='23514'; END; $$");
        try {
            jdbc.execute("CREATE TRIGGER " + fault + " BEFORE INSERT ON lifecycle_audit_events FOR EACH ROW "
                    + "WHEN (NEW.action = 'CREATED') EXECUTE FUNCTION " + fault + "()");
            assertThrows(org.springframework.dao.DataAccessException.class, () -> mediator.send(
                    new CreateDraftCommand("EXPENSE", Map.of("amount", 100, "description", "must roll back"), caller)));
            assertEquals(beforeRequests, jdbc.queryForObject("SELECT count(*) FROM requests", Long.class));
            assertEquals(beforeCoverage,
                    jdbc.queryForObject("SELECT count(*) FROM request_audit_coverage", Long.class));
            assertEquals(beforeEvents, jdbc.queryForObject("SELECT count(*) FROM lifecycle_audit_events", Long.class));
        }
        finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + fault + " ON lifecycle_audit_events");
            jdbc.execute("DROP FUNCTION " + fault + "()");
        }
    }

}
