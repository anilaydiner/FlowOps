package com.flowops;

import com.flowops.persistence.ExecutionJobJpaRepository;
import com.flowops.application.audit.*;
import com.flowops.application.audit.model.LifecycleAuditRecord;
import com.flowops.application.audit.port.LifecycleAuditRepository;
import com.flowops.application.execution.ProcessExecutionJobs;
import com.flowops.application.mediator.Mediator;
import com.flowops.application.request.command.*;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.port.UserRepository;
import com.flowops.domain.request.DecisionAction;
import com.flowops.domain.request.Request;
import com.flowops.domain.execution.port.ExecutionJobRepository;
import com.flowops.action.SimulatedActionHandler;
import com.flowops.worker.JobWorker;
import com.flowops.worker.OutboxDispatcher;
import com.flowops.worker.SlaScanner;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class LifecycleAuditStorageTest extends AbstractIntegrationTest {

    @MockitoBean
    JobWorker backgroundJobs;

    @MockitoBean
    OutboxDispatcher backgroundOutbox;

    @MockitoBean
    SlaScanner backgroundSla;

    @Autowired
    private ExecutionJobJpaRepository jobRows;

    @Autowired
    Mediator mediator;

    @Autowired
    UserRepository users;

    @Autowired
    LifecycleAuditRepository history;

    @Autowired
    AuditService audit;

    @Autowired
    ProcessExecutionJobs jobs;

    @Autowired
    ExecutionJobRepository jobRepository;

    @Autowired
    SimulatedActionHandler actions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactions;

    private CallerIdentity caller(String name) {
        var user = users.findByUsername(name).orElseThrow();
        return new CallerIdentity(user.getId(), user.getUsername());
    }

    private Request draft() {
        return mediator
            .send(new CreateDraftCommand("EXPENSE", Map.of("amount", 100, "description", "Audit test"),
                    caller("alice")))
            .request();
    }

    private Request approved() {
        Request request = draft();
        mediator.send(new SubmitRequestCommand(request.getId(), caller("alice")));
        return mediator
            .send(new DecideStepCommand(request.getId(), DecisionAction.APPROVE, "Policy checked", null,
                    caller("admin")))
            .request();
    }

    @Test
    void everyRequestTransitionIsStoredWithBeforeAndAfterAndHistoricalIdentity() {
        Request request = draft();
        mediator.send(new UpdateDraftCommand(request.getId(),
                Map.of("amount", 101, "description", "Changed", "externalToken", "payload-value-must-not-be-copied"),
                caller("alice")));
        mediator.send(new SubmitRequestCommand(request.getId(), caller("alice")));
        mediator.send(new DecideStepCommand(request.getId(), DecisionAction.APPROVE, "Policy checked", null,
                caller("admin")));
        jobs.execute();
        mediator.send(new CloseRequestCommand(request.getId(), caller("alice")));
        var events = history.findByRequestId(request.getId());
        assertTrue(events.stream().allMatch(e -> e.provenance().equals("RECORDED")));
        assertEquals(List.of("DRAFT", "SUBMITTED", "IN_REVIEW", "APPROVED", "EXECUTED", "CLOSED"),
                events.stream()
                    .filter(e -> e.type().equals("STATUS") || e.type().equals("SUBMITTED"))
                    .map(e -> (String) e.after().get("status"))
                    .toList());
        assertTrue(events.stream()
            .anyMatch(e -> e.action().equals("DRAFT_UPDATED")
                    && Boolean.FALSE.equals(e.details().get("payloadValuesRecorded"))));
        assertTrue(
                events.stream().anyMatch(e -> e.type().equals("PARTICIPANT") && e.action().equals("ROSTER_CAPTURED")));
        assertTrue(events.stream()
            .anyMatch(e -> e.type().equals("JOB") && e.action().equals("STARTED") && e.actor().equals("system")));
        assertFalse(events.toString().contains("payload-value-must-not-be-copied"));
        var decision = events.stream().filter(e -> e.type().equals("DECISION")).findFirst().orElseThrow();
        assertEquals("admin", decision.actor());
        assertEquals(caller("admin").id(), decision.actorId());
        assertEquals("Policy checked", decision.reason());
        // Later activity/identity edits cannot rewrite the stored account of creation or
        // approval.
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("UPDATE requests SET updated_at=updated_at+interval '1 second' WHERE id=?", request.getId());
            jdbc.update("UPDATE users SET username=? WHERE id=?",
                    "renamed_" + UUID.randomUUID().toString().substring(0, 8), decision.actorId());
            assertEquals(events, history.findByRequestId(request.getId()));
            assertTrue(audit.timeline(request.getId(), null, null, "DECISION", caller("alice"))
                .stream()
                .allMatch(e -> e.actor().equals("admin")));
            status.setRollbackOnly();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = { "UPDATE lifecycle_audit_events SET summary='edited' WHERE request_id=?",
            "DELETE FROM lifecycle_audit_events WHERE request_id=?",
            "DELETE FROM request_audit_coverage WHERE request_id=?" })
    @Transactional
    void historyAndCoverageAreAppendOnly(String sql) {
        Request request = draft();
        assertThrows(DataAccessException.class, () -> jdbc.update(sql, request.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = { "lifecycle_audit_events", "request_audit_coverage" })
    @Transactional
    void truncationIsRejected(String table) {
        draft();
        assertThrows(DataAccessException.class, () -> jdbc.execute("TRUNCATE " + table));
    }

    @Test
    @Transactional
    void duplicateFactKeyNeverRewritesTheOriginalRecord() {
        Request request = draft();
        LifecycleAuditRecord event = history.findByRequestId(request.getId()).getFirst();
        assertFalse(history.appendIfAbsent(event));
        var replay = new LifecycleAuditRecord(UUID.randomUUID(), null, event.key(), event.requestId(), event.type(),
                event.action(), Instant.now(), event.actorId(), "different", "HUMAN", null, null, null, null,
                "changed summary", Map.of(), Map.of(), Map.of(), "RECORDED");
        assertFalse(history.appendIfAbsent(replay));
        assertEquals(List.of(event), history.findByRequestId(request.getId()));
    }

    @Test
    void jobRetriesPreserveEachCommittedAttempt() {
        Request request = approved();
        actions.setSimulatedFailure(true, 1);
        try {
            jobs.execute();
            var firstFailure = history.findByRequestId(request.getId())
                .stream()
                .filter(e -> e.type().equals("JOB") && e.action().equals("FAILED"))
                .findFirst()
                .orElseThrow();
            var job = jobRepository.findByRequestIdOrderByCreatedAtAsc(request.getId()).getFirst();
            new org.springframework.transaction.support.TransactionTemplate(transactions)
                .executeWithoutResult(status -> jobRows.resetNextRetryAt(job.getId(), Instant.EPOCH));
            actions.clearSimulatedFailure();
            try (var origin = AuditRequestOrigin.open("unrelated-http", "203.0.113.9", "unrelated-agent")) {
                jobs.execute();
            }
            var after = history.findByRequestId(request.getId());
            assertTrue(after.contains(firstFailure));
            assertEquals(2, after.stream().filter(e -> e.type().equals("JOB") && e.action().equals("STARTED")).count());
            assertTrue(after.stream().anyMatch(e -> e.type().equals("JOB") && e.action().equals("COMPLETED")));
            assertTrue(after.stream()
                .filter(e -> e.type().equals("JOB"))
                .allMatch(e -> e.actorKind().equals("SYSTEM") && e.remoteAddress() == null && e.userAgent() == null));
        }
        finally {
            actions.clearSimulatedFailure();
        }
    }

    private String token() throws Exception {
        var result = mvc
            .perform(post("/api/v1/auth/token").contentType("application/json")
                .content("{\"username\":\"alice\",\"password\":\"alice123\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return new tools.jackson.databind.ObjectMapper().readTree(result).get("token").asText();
    }

    @Test
    @Transactional
    void httpOriginIsCapturedAndRemovedWithoutTrustingForwardedHeaders() throws Exception {
        String token = token();
        var body = mvc.perform(post("/api/v1/requests").header("Authorization", "Bearer " + token)
            .header("X-Correlation-Id", "audit-origin-check")
            .header("User-Agent", "FlowOps-Audit-Test")
            .header("X-Forwarded-For", "198.51.100.99")
            .with(r -> {
                r.setRemoteAddr("192.0.2.21");
                return r;
            })
            .contentType("application/json")
            .content("{\"typeCode\":\"EXPENSE\",\"payload\":{\"amount\":100,\"description\":\"Origin\"}}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
        long id = new tools.jackson.databind.ObjectMapper().readTree(body).get("id").asLong();
        var event = history.findByRequestId(id).getFirst();
        assertEquals("audit-origin-check", event.correlationId());
        assertEquals("192.0.2.21", event.remoteAddress());
        assertEquals("FlowOps-Audit-Test", event.userAgent());
        assertEquals("alice", event.actor());
        assertNull(AuditRequestOrigin.current());
        mvc.perform(put("/api/v1/requests/" + id).header("Authorization", "Bearer " + token)
            .contentType("application/json")
            .content("{\"payload\":{\"amount\":101,\"description\":\"Second\"}}")).andExpect(status().isOk());
        var second = history.findByRequestId(id)
            .stream()
            .filter(e -> e.action().equals("DRAFT_UPDATED"))
            .findFirst()
            .orElseThrow();
        assertNull(second.userAgent());
        assertNotEquals(event.correlationId(), second.correlationId());
        assertNull(AuditRequestOrigin.current());
    }

}
