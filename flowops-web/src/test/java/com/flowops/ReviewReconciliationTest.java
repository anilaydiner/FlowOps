package com.flowops;

import com.flowops.application.request.port.RequestWriteLock;
import com.flowops.application.request.lifecycle.*;
import com.flowops.application.request.recovery.*;
import com.flowops.application.request.sla.*;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.identity.*;
import com.flowops.domain.identity.port.*;
import com.flowops.domain.organization.port.DepartmentRepository;
import com.flowops.domain.request.*;
import com.flowops.domain.request.port.*;
import com.flowops.domain.workflow.*;
import com.flowops.domain.workflow.port.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class ReviewReconciliationTest extends AbstractIntegrationTest {

    @Autowired
    org.springframework.test.web.servlet.MockMvc mvc;

    @MockitoBean
    Clock clock;

    @Autowired
    UserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    PermissionRepository permissions;

    @Autowired
    DepartmentRepository departments;

    @Autowired
    WorkflowRepository workflows;

    @Autowired
    WorkflowStepRepository definitions;

    @Autowired
    RequestTypeRepository types;

    @Autowired
    RequestRepository requests;

    @Autowired
    RequestWriteLock requestWriteLock;

    @Autowired
    StepInstanceRepository steps;

    @Autowired
    StepParticipantRepository participants;

    @Autowired
    DecisionRepository decisions;

    @Autowired
    SubmitRequest submit;

    @Autowired
    DecideStep decide;

    @Autowired
    CancelRequest cancel;

    @Autowired
    RecoverBlockedRequest recovery;

    @Autowired
    ReconcileReviewRequest processor;

    @Autowired
    ReconcileEligibility reconciliation;

    @Autowired
    ScanSla scanner;

    @Autowired
    com.flowops.application.request.port.ApprovalQueueQueries queue;

    @Autowired
    com.flowops.application.request.ApprovalEligibilityService eligibility;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    io.micrometer.core.instrument.MeterRegistry meters;

    private final Instant start = Instant.parse("2026-12-01T12:00:00Z");

    @BeforeEach
    void clock() {
        now(start);
    }

    private void now(Instant time) {
        when(clock.instant()).thenReturn(time);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactions);
    }

    private record Fixture(Long requestId, Long stepId, Long reviewerId) {
    }

    private CallerIdentity caller(Long id) {
        var u = users.findById(id).orElseThrow();
        return new CallerIdentity(id, u.getUsername());
    }

    private CallerIdentity caller(String name) {
        return caller(users.findByUsername(name).orElseThrow().getId());
    }

    private Fixture fixture(Integer sla, Integer expiry) {
        return fixture(sla, expiry, 60, null);
    }

    private Fixture fixture(Integer sla, Integer expiry, int interval, Integer maxLevels) {
        return tx().execute(status -> {
            String key = "RR_" + UUID.randomUUID().toString().substring(0, 8);
            var role = new Role(key, "Review reconciliation", null);
            role.setPermissions(Set.of(permissions.findAll()
                .stream()
                .filter(p -> p.getCode().equals("request:approve") && p.getScopeType() == null)
                .findFirst()
                .orElseThrow()));
            roles.save(role);
            var reviewer = users.save(User.newUser(key.toLowerCase(), "Reviewer", null, null, Set.of(role), "unused"));
            var workflow = workflows.save(new Workflow(key, "Review reconciliation", null));
            var definition = new WorkflowStep(workflow, 1, "REVIEW", "Review", key);
            definition.configure(CompletionRule.ANY_OF, 1, Map.of(), sla, null, false);
            definition.setExpiryMinutes(expiry);
            definition.setEscalationIntervalMinutes(interval);
            definition.setMaxEscalationLevels(maxLevels);
            definitions.save(definition);
            var type = new RequestType(key, "Review reconciliation", null,
                    departments.findByCode("FINANCE").orElseThrow());
            type.setDefaultWorkflow(workflow);
            types.save(type);
            var request = requests
                .save(Request.newDraft(type, users.findByUsername("alice").orElseThrow(), Map.of(), null));
            submit.execute(request.getId(), caller("alice"));
            return new Fixture(request.getId(), steps.findByRequestIdOrderById(request.getId()).getFirst().getId(),
                    reviewer.getId());
        });
    }

    private StepInstance step(Fixture f) {
        return tx().execute(s -> steps.findByRequestIdOrderById(f.requestId()).getFirst());
    }

    private Request request(Fixture f) {
        return tx().execute(s -> requests.findById(f.requestId()).orElseThrow());
    }

    private void active(Fixture f, boolean value) {
        tx().executeWithoutResult(s -> {
            var u = users.findById(f.reviewerId()).orElseThrow();
            u.setActive(value);
            users.save(u);
        });
    }

    private int events(Fixture f, String action) {
        return jdbc.queryForObject("SELECT count(*) FROM lifecycle_audit_events WHERE request_id=? AND action=?",
                Integer.class, f.requestId(), action);
    }

    @Test
    void overdueReviewEscalatesOnceAndNeverExpiresWithoutBusinessDeadline() {
        var f = fixture(10, null, 60, 1);
        now(start.plusSeconds(10 * 60));
        assertTrue(processor.process(f.requestId(), false).breaches() > 0);
        long version = request(f).getVersion();
        now(start.plusSeconds(100 * 60));
        assertEquals(0, processor.process(f.requestId(), false).breaches());
        assertEquals(RequestStatus.IN_REVIEW, request(f).getStatus());
        assertEquals(version, request(f).getVersion());
        assertNull(step(f).getExpiresAt());
        assertNull(step(f).getNextEscalationAt());
        assertEquals(1, events(f, "BREACHED"));
    }

    @Test
    void reconciliationBlocksAndAutomaticallyRestoresTheSameRosterAsASystemAction() {
        var f = fixture(10, 60);
        active(f, false);
        now(start.plusSeconds(11 * 60));
        processor.process(f.requestId(), true);
        assertEquals(StepState.BLOCKED, step(f).getState());
        assertEquals(1, events(f, "BLOCKED"));
        assertEquals(1, events(f, "BREACHED"));
        var blockedAt = step(f).getBlockedAt();
        processor.process(f.requestId(), true);
        assertEquals(blockedAt, step(f).getBlockedAt());
        assertEquals(1, events(f, "BLOCKED"));
        active(f, true);
        processor.process(f.requestId(), true);
        assertEquals(StepState.PENDING, step(f).getState());
        assertEquals(start, step(f).getActivatedAt());
        assertEquals(start.plusSeconds(600), step(f).getReviewDueAt());
        assertEquals(start.plusSeconds(3600), step(f).getExpiresAt());
        assertEquals(0, decisions.countDistinctApprovers(f.stepId()));
        var history = participants.findRecoveriesByRequestId(f.requestId());
        assertEquals(1, history.size());
        assertNull(history.getFirst().actorId());
        assertEquals("SYSTEM",
                jdbc.queryForObject(
                        "SELECT actor_kind FROM lifecycle_audit_events WHERE request_id=? AND action='RECOVERED'",
                        String.class, f.requestId()));
    }

    @Test
    void exactBusinessDeadlineStopsBlockedWorkBeforeItCanRecover() {
        var f = fixture(null, 5);
        active(f, false);
        processor.process(f.requestId(), true);
        assertEquals(StepState.BLOCKED, step(f).getState());
        active(f, true);
        now(start.plusSeconds(300));
        assertThrows(ConflictException.class, () -> recovery.recover(f.requestId(), f.stepId(), request(f).getVersion(),
                "Too late", true, List.of(), caller("admin")));
        assertEquals(1, processor.process(f.requestId(), true).expiries());
        assertEquals(RequestStatus.EXPIRED, request(f).getStatus());
        assertEquals(StepState.CANCELLED, step(f).getState());
        assertTrue(participants.findRecoveriesByRequestId(f.requestId()).isEmpty());
        assertEquals(0, processor.process(f.requestId(), true).expiries());
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM lifecycle_audit_events WHERE request_id=? AND after_data->>'status'='EXPIRED'",
                Integer.class, f.requestId()));
    }

    @Test
    void lateApprovalCannotBeatBusinessExpiryEvenBeforeTheScannerRuns() {
        var f = fixture(60, 1);
        assertTrue(queue
            .findPage(com.flowops.domain.paging.PageQuery.first(100),
                    eligibility.authority(f.reviewerId(), DecisionAction.APPROVE))
            .content()
            .stream()
            .anyMatch(r -> r.id().equals(f.requestId())));
        now(start.plusSeconds(60));
        assertTrue(queue
            .findPage(com.flowops.domain.paging.PageQuery.first(100),
                    eligibility.authority(f.reviewerId(), DecisionAction.APPROVE))
            .content()
            .isEmpty());
        assertThrows(ConflictException.class,
                () -> decide.execute(f.requestId(), DecisionAction.APPROVE, "Late", caller(f.reviewerId())));
        processor.process(f.requestId(), false);
        assertEquals(RequestStatus.EXPIRED, request(f).getStatus());
        assertEquals(0, decisions.countDistinctApprovers(f.stepId()));
    }

    @Test
    void backgroundProcessingSkipsLockedParentsAndRechecksStoppedRequests() throws Exception {
        var f = fixture(1, 2);
        now(start.plusSeconds(120));
        try (var pool = Executors.newSingleThreadExecutor()) {
            tx().executeWithoutResult(s -> {
                requestWriteLock.findByIdForUpdate(f.requestId()).orElseThrow();
                try {
                    assertFalse(pool.submit(() -> processor.process(f.requestId(), true))
                        .get(3, TimeUnit.SECONDS)
                        .processed());
                }
                catch (Exception e) {
                    throw new AssertionError(e);
                }
                cancel.execute(f.requestId(), caller("alice"));
            });
        }
        assertFalse(processor.process(f.requestId(), true).processed());
        assertEquals(RequestStatus.CANCELLED, request(f).getStatus());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM lifecycle_audit_events WHERE request_id=? AND after_data->>'status'='EXPIRED'",
                Integer.class, f.requestId()));
    }

    @Test
    void boundedSweepEventuallyReachesLaterRequests() {
        var a = fixture(null, null);
        var b = fixture(null, null);
        active(a, false);
        active(b, false);
        var bounded = new ReconcileEligibility(steps, processor, 1);
        boolean reached = false;
        for (int i = 0; i < 1000; i++) {
            var result = bounded.scan();
            assertTrue(result.discovered() <= 1);
            if (step(b).getState() == StepState.BLOCKED) {
                reached = true;
                break;
            }
        }
        assertTrue(reached);
        assertEquals(StepState.BLOCKED, step(a).getState());
    }

    @Test
    void businessDeadlineAndActivationCannotBeRewritten() {
        var f = fixture(1, 5);
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc
            .update("UPDATE step_instances SET expires_at=expires_at+interval '1 minute' WHERE id=?", f.stepId()));
        assertEquals(start.plusSeconds(300), step(f).getExpiresAt());
    }

    @Test
    void oneFailedRequestDoesNotRollBackOrStarveOtherRequests() {
        var a = fixture(null, null);
        var b = fixture(null, null);
        active(a, false);
        active(b, false);
        String fault = "reconcile_fault_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + fault
                + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected audit failure' USING ERRCODE='23514'; END; $$");
        try {
            jdbc.execute("CREATE TRIGGER " + fault + " BEFORE INSERT ON lifecycle_audit_events FOR EACH ROW WHEN "
                    + "(NEW.request_id=" + a.requestId() + " AND NEW.action='BLOCKED') EXECUTE FUNCTION " + fault
                    + "()");
            var driver = new ReconcileEligibility(steps, processor, 500);
            int failures = 0;
            for (int i = 0; i < 10 && step(b).getState() != StepState.BLOCKED; i++)
                failures += driver.scan().failures();
            assertTrue(failures > 0);
            assertEquals(StepState.PENDING, step(a).getState());
            assertEquals(0, events(a, "BLOCKED"));
            assertEquals(StepState.BLOCKED, step(b).getState());
        }
        finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + fault + " ON lifecycle_audit_events");
            jdbc.execute("DROP FUNCTION " + fault + "()");
        }
    }

    @Test
    void workflowApiAcceptsIndependentExpiryAndRejectsNonpositiveExpiry() throws Exception {
        var principal = new com.flowops.security.UserPrincipal(caller("admin").id(), "admin", Set.of(), null);
        var authentication = new org.springframework.security.authentication.TestingAuthenticationToken(principal, null,
                "ROLE_TEST");
        String code = "DEADLINE_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String body = "{\"code\":\"" + code
                + "\",\"name\":\"Deadline configuration\",\"steps\":[{\"code\":\"REVIEW\",\"name\":\"Review\",\"requiredRole\":\"ADMIN\",\"slaMinutes\":60,\"expiryMinutes\":5,\"escalationIntervalMinutes\":5,\"maxEscalationLevels\":3}]}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/workflows")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .authentication(authentication))
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content(body))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.steps[0].expiryMinutes")
                .value(5))
            .andExpect(
                    org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.steps[0].slaMinutes")
                        .value(60))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.steps[0].escalationIntervalMinutes")
                .value(5))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.steps[0].maxEscalationLevels")
                .value(3));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/workflows")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .authentication(authentication))
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content(body.replace("\"expiryMinutes\":5", "\"expiryMinutes\":0")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        for (String field : List.of("escalationIntervalMinutes", "maxEscalationLevels")) {
            String value = field.equals("maxEscalationLevels") ? "3" : "5";
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/workflows")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .authentication(authentication))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(body.replace("\"" + field + "\":" + value, "\"" + field + "\":0")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        }
    }

    private int escalationMessages(Fixture f) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE idempotency_key LIKE ?", Integer.class,
                "req:" + f.requestId() + ":SLA_BREACH%");
    }

    @Test
    void recurringLevelsBroadenRecipientsAndDoNotRepeatBeforeDue() {
        var f = fixture(10, null, 5, null);
        now(start.plusSeconds(600));
        assertEquals(1, processor.process(f.requestId(), false).breaches());
        assertEquals(1, step(f).getEscalationLevel());
        assertEquals(start.plusSeconds(900), step(f).getNextEscalationAt());
        assertEquals(2, escalationMessages(f));
        now(start.plusSeconds(899));
        assertEquals(0, processor.process(f.requestId(), false).breaches());
        now(start.plusSeconds(900));
        assertTrue(processor.process(f.requestId(), false).breaches() >= 2);
        assertEquals(2, step(f).getEscalationLevel());
        assertEquals(1,
                jdbc.queryForObject("SELECT count(*) FROM outbox WHERE recipient_id=? AND idempotency_key LIKE ?",
                        Integer.class, caller("admin").id(), "req:" + f.requestId() + ":SLA_BREACH:%:level:2"));
        int messages = escalationMessages(f);
        long version = request(f).getVersion();
        processor.process(f.requestId(), false);
        assertEquals(messages, escalationMessages(f));
        assertEquals(version, request(f).getVersion());
        now(start.plusSeconds(1200));
        processor.process(f.requestId(), false);
        assertEquals(3, step(f).getEscalationLevel());
        assertEquals(3, events(f, "BREACHED"));
        assertEquals(RequestStatus.IN_REVIEW, request(f).getStatus());
    }

    @Test
    void blockedWorkWithoutSlaEscalatesByBlockedAgeAndStopsRemindersOnRecovery() {
        var f = fixture(null, null, 5, null);
        active(f, false);
        processor.process(f.requestId(), true);
        assertNull(step(f).getReviewDueAt());
        assertEquals(start.plusSeconds(300), step(f).getNextEscalationAt());
        now(start.plusSeconds(299));
        assertEquals(0, processor.process(f.requestId(), true).breaches());
        now(start.plusSeconds(300));
        assertTrue(processor.process(f.requestId(), true).breaches() > 0);
        now(start.plusSeconds(600));
        assertTrue(processor.process(f.requestId(), true).breaches() > 0);
        assertEquals(2, step(f).getEscalationLevel());
        assertEquals(2, events(f, "ESCALATED"));
        assertEquals(0, events(f, "BREACHED"));
        active(f, true);
        processor.process(f.requestId(), true);
        assertEquals(StepState.PENDING, step(f).getState());
        assertNull(step(f).getNextEscalationAt());
        now(start.plusSeconds(6000));
        processor.process(f.requestId(), true);
        assertEquals(2, events(f, "ESCALATED"));
        assertEquals(RequestStatus.IN_REVIEW, request(f).getStatus());
        active(f, false);
        processor.process(f.requestId(), true);
        assertEquals(2, step(f).getBlockEpisode());
        assertEquals(0, step(f).getEscalationLevel());
        assertEquals(start.plusSeconds(6300), step(f).getNextEscalationAt());
    }

    @Test
    void delayedScanEmitsOneLevelAndAnExplicitCapStopsFurtherLevels() {
        var f = fixture(10, null, 5, 2);
        now(start.plusSeconds(60000));
        processor.process(f.requestId(), false);
        assertEquals(1, step(f).getEscalationLevel());
        assertEquals(start.plusSeconds(60300), step(f).getNextEscalationAt());
        processor.process(f.requestId(), false);
        assertEquals(1, events(f, "BREACHED"));
        now(start.plusSeconds(60300));
        processor.process(f.requestId(), false);
        assertEquals(2, step(f).getEscalationLevel());
        assertNull(step(f).getNextEscalationAt());
        now(start.plusSeconds(100000));
        processor.process(f.requestId(), false);
        assertEquals(2, events(f, "BREACHED"));
        assertEquals(RequestStatus.IN_REVIEW, request(f).getStatus());
    }

    @Test
    void concurrentScannersCommitOneLevelAndOneNotificationSet() throws Exception {
        var f = fixture(1, null, 5, null);
        now(start.plusSeconds(60));
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++)
                results.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return processor.process(f.requestId(), false);
                }));
            for (var result : results)
                result.get(10, TimeUnit.SECONDS);
        }
        assertEquals(1, step(f).getEscalationLevel());
        assertEquals(1, events(f, "BREACHED"));
        assertEquals(2, escalationMessages(f));
    }

    @Test
    void recurringEscalationRollsBackLevelScheduleAndOutboxWhenAuditFails() {
        var f = fixture(1, null, 5, null);
        now(start.plusSeconds(60));
        processor.process(f.requestId(), false);
        var due = step(f).getNextEscalationAt();
        long version = request(f).getVersion();
        int messages = escalationMessages(f);
        now(due);
        double beforeMetrics = meters.get(com.flowops.observability.MetricNames.SLA_BREACH)
            .tag("step", "REVIEW")
            .counter()
            .count();
        String fault = "escalation_fault_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + fault
                + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'escalation audit failure' USING ERRCODE='23514'; END; $$");
        try {
            jdbc.execute("CREATE TRIGGER " + fault + " BEFORE INSERT ON lifecycle_audit_events FOR EACH ROW WHEN "
                    + "(NEW.request_id=" + f.requestId()
                    + " AND NEW.action='BREACHED' AND NEW.details->>'level'='2') EXECUTE FUNCTION " + fault + "()");
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> processor.process(f.requestId(), false));
            assertEquals(1, step(f).getEscalationLevel());
            assertEquals(due, step(f).getNextEscalationAt());
            assertEquals(version, request(f).getVersion());
            assertEquals(messages, escalationMessages(f));
            assertEquals(beforeMetrics,
                    meters.get(com.flowops.observability.MetricNames.SLA_BREACH)
                        .tag("step", "REVIEW")
                        .counter()
                        .count());
        }
        finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + fault + " ON lifecycle_audit_events");
            jdbc.execute("DROP FUNCTION " + fault + "()");
        }
        processor.process(f.requestId(), false);
        assertEquals(2, step(f).getEscalationLevel());
        assertEquals(2, events(f, "BREACHED"));
    }

    @Test
    void missingRecipientsBackOffWithoutConsumingALevelAndCanRecover() {
        var f = fixture(null, null, 5, null);
        active(f, false);
        processor.process(f.requestId(), true);
        Long admin = caller("admin").id();
        try {
            tx().executeWithoutResult(s -> {
                var user = users.findById(admin).orElseThrow();
                user.setActive(false);
                users.save(user);
            });
            now(start.plusSeconds(300));
            processor.process(f.requestId(), true);
            assertEquals(0, step(f).getEscalationLevel());
            assertEquals(start.plusSeconds(600), step(f).getNextEscalationAt());
            assertEquals(1, events(f, "RECIPIENTS_UNAVAILABLE"));
            assertEquals(0, escalationMessages(f));
            processor.process(f.requestId(), true);
            assertEquals(1, events(f, "RECIPIENTS_UNAVAILABLE"));
        }
        finally {
            tx().executeWithoutResult(s -> {
                var user = users.findById(admin).orElseThrow();
                user.setActive(true);
                users.save(user);
            });
        }
        now(start.plusSeconds(600));
        processor.process(f.requestId(), true);
        assertEquals(1, step(f).getEscalationLevel());
        assertEquals(1, events(f, "ESCALATED"));
    }

    @Test
    void explicitExpiryAtARecurringBoundaryWinsAndPolicyCannotBeRewritten() {
        var f = fixture(1, 6, 5, null);
        now(start.plusSeconds(60));
        processor.process(f.requestId(), false);
        assertEquals(start.plusSeconds(360), step(f).getNextEscalationAt());
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("UPDATE step_instances SET escalation_interval_minutes=1 WHERE id=?", f.stepId()));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("UPDATE step_instances SET max_escalation_levels=1 WHERE id=?", f.stepId()));
        now(start.plusSeconds(360));
        processor.process(f.requestId(), false);
        assertEquals(RequestStatus.EXPIRED, request(f).getStatus());
        assertEquals(1, events(f, "BREACHED"));
    }

    @Test
    void requesterOnlyEscalationRoleStillFallsBackToRecoveryOperators() {
        var f = fixture(1, null, 5, null);
        active(f, false);
        var owner = users.findByUsername("alice").orElseThrow();
        var original = new HashSet<>(owner.getRoles());
        var escalationRole = step(f).getRequiredRole();
        try {
            tx().executeWithoutResult(status -> {
                var updated = users.findById(owner.getId()).orElseThrow();
                var assigned = new HashSet<>(updated.getRoles());
                assigned.add(roles.findByCode(escalationRole).orElseThrow());
                updated.setRoles(assigned);
                users.save(updated);
            });
            now(start.plusSeconds(60));
            assertTrue(processor.process(f.requestId(), false).breaches() > 0);
            assertEquals(1, step(f).getEscalationLevel());
            assertEquals(1,
                    jdbc.queryForObject("SELECT count(*) FROM outbox WHERE recipient_id=? AND idempotency_key LIKE ?",
                            Integer.class, caller("admin").id(), "req:" + f.requestId() + ":SLA_BREACH:%:level:1"));
            assertEquals(0,
                    jdbc.queryForObject("SELECT count(*) FROM outbox WHERE recipient_id=? AND idempotency_key LIKE ?",
                            Integer.class, owner.getId(), "req:" + f.requestId() + ":SLA_BREACH%"));
        }
        finally {
            tx().executeWithoutResult(status -> {
                var restored = users.findById(owner.getId()).orElseThrow();
                restored.setRoles(original);
                users.save(restored);
            });
        }
    }

}
