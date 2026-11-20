package com.flowops;

import com.flowops.application.execution.*;
import com.flowops.application.execution.ExecutionRecoveryTransactions.*;
import com.flowops.application.execution.model.ExecutionRecovery.*;
import com.flowops.application.execution.port.ExecutionRecoveryRepository;
import com.flowops.application.mediator.Mediator;
import com.flowops.application.request.command.*;
import com.flowops.application.request.lifecycle.CancelRequest;
import com.flowops.domain.exception.*;
import com.flowops.domain.execution.*;
import com.flowops.domain.execution.port.*;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.port.UserRepository;
import com.flowops.domain.request.*;
import com.flowops.domain.request.port.RequestRepository;
import com.flowops.security.UserPrincipal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import static com.flowops.domain.execution.ProviderObservation.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

@AutoConfigureMockMvc
class ExecutionRecoveryTest extends AbstractIntegrationTest {

    @MockitoBean
    ActionHandler handler;

    @MockitoBean
    Clock clock;

    @Autowired
    RecoverExecution recovery;

    @Autowired
    ExecutionRecoveryTransactions recoveryTx;

    @Autowired
    ExecutionRecoveryRepository records;

    @Autowired
    ExecutionRecoveryHistory history;

    @Autowired
    Mediator mediator;

    @Autowired
    UserRepository users;

    @Autowired
    RequestRepository requests;

    @Autowired
    ExecutionJobRepository jobs;

    @Autowired
    StartExecutionJob start;

    @Autowired
    FinishExecutionJob finish;

    @Autowired
    ProcessExecutionJobs worker;

    @Autowired
    CancelRequest cancel;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    MockMvc mvc;

    private final Instant now = Instant.parse("2026-12-03T12:00:00Z");

    private record Fixture(Long requestId, Long jobId) {
        ExecutionJobRepository.Candidate candidate() {
            return new ExecutionJobRepository.Candidate(jobId, requestId);
        }
    }

    private CallerIdentity caller(String name) {
        var u = users.findByUsername(name).orElseThrow();
        return new CallerIdentity(u.getId(), u.getUsername());
    }

    private Request request(Fixture f) {
        return requests.findById(f.requestId()).orElseThrow();
    }

    private ExecutionJob job(Fixture f) {
        return jobs.findById(f.jobId()).orElseThrow();
    }

    private Input input(Fixture f) {
        return new Input(UUID.randomUUID(), request(f).getVersion(), "Investigated provider outcome");
    }

    private ProviderObservation proof(ProviderObservation.Outcome outcome) {
        return new ProviderObservation(outcome, "provider:receipt-123", Map.of("ticketId", "TCK-123"));
    }

    private Result reconcile(Fixture f) {
        return recovery.reconcile(f.requestId(), f.jobId(), input(f), caller("admin"));
    }

    private Result retry(Fixture f) {
        return recovery.retry(f.requestId(), f.jobId(), input(f), caller("admin"));
    }

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(now);
        when(handler.supportsIdempotentRetries()).thenReturn(true);
        TestProviderContracts.stub(handler);
        when(handler.execute(any())).thenReturn(Map.of("ok", true));
        when(handler.reconcile(any())).thenReturn(ProviderObservation.unknown());
        for (int i = 0; i < 30 && !jobs.findCandidates(now, 50).isEmpty(); i++)
            worker.execute();
        clearInvocations(handler);
    }

    private Fixture approved() {
        var r = mediator
            .send(new CreateDraftCommand("EXPENSE", Map.of("amount", 100, "description", "Recovery"), caller("alice")))
            .request();
        mediator.send(new SubmitRequestCommand(r.getId(), caller("alice")));
        mediator.send(new DecideStepCommand(r.getId(), DecisionAction.APPROVE, "Reviewed", null, caller("admin")));
        return new Fixture(r.getId(), jobs.findByRequestIdOrderByCreatedAtAsc(r.getId()).getFirst().getId());
    }

    private Fixture held() {
        var f = approved();
        var claim = start.start(f.candidate()).orElseThrow();
        finish.finish(claim, null, new ActionHandler.ActionExecutionException("Uncertain"), false);
        assertEquals(JobStatus.RECONCILIATION_REQUIRED, job(f).getStatus());
        return f;
    }

    @Test
    void confirmedSuccessCompletesWithoutExecutingAndReplaysOnce() {
        var f = held();
        var latch = request(f).getExecutionStartedAt();
        var command = input(f);
        when(handler.reconcile(any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(records.find(command.commandId()).isPresent());
            assertEquals(JobStatus.RECONCILIATION_REQUIRED, job(f).getStatus());
            return proof(SUCCEEDED);
        });
        var result = recovery.reconcile(f.requestId(), f.jobId(), command, caller("admin"));
        assertEquals(Disposition.COMPLETED, result.entry().observation().disposition());
        assertEquals(JobStatus.COMPLETED, job(f).getStatus());
        assertEquals(RequestStatus.EXECUTED, request(f).getStatus());
        assertEquals(latch, request(f).getExecutionStartedAt());
        assertEquals("TCK-123", job(f).getResult().get("ticketId"));
        long version = request(f).getVersion();
        var replay = recovery.reconcile(f.requestId(), f.jobId(), command, caller("admin"));
        assertEquals(result.entry(), replay.entry());
        assertEquals(version, request(f).getVersion());
        verify(handler, times(1)).reconcile(any());
        verify(handler, never()).execute(any());
        assertEquals(1, history.get(f.requestId(), 50, caller("admin")).commands().size());
    }

    @Test
    void unknownUnsafeAndInProgressResultsStayHeld() {
        var f = held();
        when(handler.supportsIdempotentRetries()).thenReturn(false);
        assertEquals(Disposition.OBSERVED, reconcile(f).entry().observation().disposition());
        assertEquals("execution-retry-unsafe", assertThrows(ConflictException.class, () -> retry(f)).getCode());
        when(handler.supportsIdempotentRetries()).thenReturn(true);
        when(handler.reconcile(any())).thenReturn(proof(IN_PROGRESS));
        reconcile(f);
        assertEquals("provider-action-in-progress", assertThrows(ConflictException.class, () -> retry(f)).getCode());
        assertEquals(JobStatus.RECONCILIATION_REQUIRED, job(f).getStatus());
        verify(handler, never()).execute(any());
    }

    @Test
    void providerProofAuthorizesExactlyOneNonIdempotentRetryWithOriginalIdentity() {
        var f = held();
        var before = job(f);
        var latch = request(f).getExecutionStartedAt();
        when(handler.supportsIdempotentRetries()).thenReturn(false);
        when(handler.reconcile(any())).thenReturn(proof(SAFE_TO_RETRY));
        reconcile(f);
        var command = input(f);
        var result = recovery.retry(f.requestId(), f.jobId(), command, caller("admin"));
        assertEquals(Disposition.RETRY_AUTHORIZED, result.entry().observation().disposition());
        assertEquals(JobStatus.FAILED, job(f).getStatus());
        long version = request(f).getVersion();
        recovery.retry(f.requestId(), f.jobId(), command, caller("admin"));
        assertEquals(version, request(f).getVersion());
        assertEquals("execution-already-started",
                assertThrows(ConflictException.class, () -> cancel.execute(f.requestId(), caller("alice"))).getCode());
        var next = start.start(f.candidate()).orElseThrow();
        assertEquals(before.getIdempotencyKey(), next.command().idempotencyKey());
        assertEquals(before.getInputPayload(), next.command().payload());
        assertEquals(latch, request(f).getExecutionStartedAt());
        assertEquals(2, job(f).getAttemptCount());
        finish.finish(next, null, new ActionHandler.ActionExecutionException("Still uncertain"), false);
        assertFalse(records.hasProviderRetryAuthorization(f.jobId(), job(f).getAttemptCount()));
        assertEquals("reconciliation-required", assertThrows(ConflictException.class, () -> retry(f)).getCode());
    }

    @Test
    void idempotentRetryRetainsCountersAndExplicitlyExtendsExhaustedBudget() {
        var f = approved();
        var claim = start.start(f.candidate()).orElseThrow();
        jdbc.update("UPDATE execution_jobs SET max_retries=1 WHERE id=?", f.jobId());
        finish.finish(claim, null, new ActionHandler.ActionExecutionException("Budget exhausted"), true);
        assertEquals(JobStatus.DEAD_LETTER, job(f).getStatus());
        reconcile(f);
        retry(f);
        assertEquals(1, job(f).getRetryCount());
        assertEquals(2, job(f).getMaxRetries());
        var second = start.start(f.candidate()).orElseThrow();
        assertFalse(finish.finish(claim, Map.of("old", true), null, true));
        assertTrue(finish.finish(second, Map.of("recovered", true), null, true));
        assertEquals(RequestStatus.EXECUTED, request(f).getStatus());
    }

    @Test
    void liveExecutionLeaseCannotBeReconciledAndExpiredCompletionIsFenced() {
        var f = approved();
        var first = start.start(f.candidate()).orElseThrow();
        assertEquals("execution-attempt-active", assertThrows(ConflictException.class, () -> reconcile(f)).getCode());
        verify(handler, never()).reconcile(any());
        when(clock.instant()).thenReturn(now.plusSeconds(301));
        when(handler.reconcile(any())).thenReturn(proof(SUCCEEDED));
        reconcile(f);
        assertEquals(JobStatus.COMPLETED, job(f).getStatus());
        assertFalse(finish.finish(first, Map.of("late", true), null, true));
        assertEquals("TCK-123", job(f).getResult().get("ticketId"));
    }

    @Test
    void crashedLookupCanBeSupersededButOldEvidenceCannotOverwriteNewCommand() {
        var f = held();
        var oldInput = input(f);
        var old = recoveryTx.begin(f.requestId(), f.jobId(), oldInput, caller("admin"));
        assertNotNull(old.query());
        var replay = recovery.reconcile(f.requestId(), f.jobId(), oldInput, caller("admin"));
        assertNull(replay.entry().observation());
        assertEquals("reconciliation-in-progress", assertThrows(ConflictException.class, () -> reconcile(f)).getCode());
        when(clock.instant()).thenReturn(now.plusSeconds(61));
        var fresh = reconcile(f);
        var stale = recoveryTx.finish(oldInput.commandId(), proof(SUCCEEDED), null);
        assertEquals(Disposition.STALE, stale.entry().observation().disposition());
        assertEquals(JobStatus.RECONCILIATION_REQUIRED, job(f).getStatus());
        assertEquals(Disposition.OBSERVED, fresh.entry().observation().disposition());
        verify(handler, times(1)).reconcile(any());
    }

    @Test
    void providerExceptionsAreRecordedWithoutPretendingSuccessOrReplaySafety() {
        var f = held();
        when(handler.reconcile(any())).thenThrow(new IllegalStateException("provider secret"));
        var result = reconcile(f);
        assertEquals(Disposition.PROVIDER_ERROR, result.entry().observation().disposition());
        assertEquals("IllegalStateException", result.entry().observation().failureType());
        assertEquals("reconciliation-required", assertThrows(ConflictException.class, () -> retry(f)).getCode());
        assertEquals(JobStatus.RECONCILIATION_REQUIRED, job(f).getStatus());
        verify(handler, never()).execute(any());
    }

    private CallerIdentity operator(String scope) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Long role = jdbc.queryForObject("INSERT INTO roles(code,name) VALUES (?,?) RETURNING id", Long.class,
                "REC_" + suffix.substring(0, 24), "Recovery test");
        Long user = jdbc.queryForObject(
                "INSERT INTO users(username,display_name,password_hash) VALUES (?,?,'unused') RETURNING id", Long.class,
                "rec_" + suffix, "Recovery operator");
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (?,?)", user, role);
        for (String permission : List.of("execution:read", "execution:reconcile", "execution:retry")) {
            Long p = jdbc.queryForObject(
                    "INSERT INTO permissions(code,name,scope_type,scope_value) VALUES (?,?,'REQUEST_TYPE',?) RETURNING id",
                    Long.class, permission + ":" + suffix, permission, scope);
            jdbc.update("INSERT INTO role_permissions(role_id,permission_id) VALUES (?,?)", role, p);
        }
        return new CallerIdentity(user, "rec_" + suffix, Set.of("ADMIN"));
    }

    @Test
    void currentScopedAuthorityAndActivityOverrideStaleTokenRoles() {
        var f = held();
        var wrong = operator("IT_ACCESS");
        var right = operator("EXPENSE");
        assertThrows(ForbiddenException.class, () -> recovery.reconcile(f.requestId(), f.jobId(), input(f), wrong));
        assertThrows(ForbiddenException.class, () -> history.get(f.requestId(), 50, wrong));
        assertThrows(ForbiddenException.class,
                () -> recovery.reconcile(f.requestId(), f.jobId(), input(f), caller("alice")));
        var command = input(f);
        recovery.reconcile(f.requestId(), f.jobId(), command, right);
        jdbc.update("UPDATE users SET active=false WHERE id=?", right.id());
        assertThrows(ForbiddenException.class, () -> recovery.reconcile(f.requestId(), f.jobId(), command, right));
        assertThrows(ForbiddenException.class, () -> history.get(f.requestId(), 50, right));
        verify(handler, times(1)).reconcile(any());
    }

    @Test
    void providerQueryDoesNotHoldLocksAndRevocationPreventsApplyingItsResult() throws Exception {
        var f = held();
        var operator = operator("EXPENSE");
        var command = input(f);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(handler.reconcile(any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return proof(SUCCEEDED);
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var running = pool.submit(() -> recovery.reconcile(f.requestId(), f.jobId(), command, operator));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var duplicate = pool.submit(() -> recovery.reconcile(f.requestId(), f.jobId(), command, operator));
                assertNull(duplicate.get(3, TimeUnit.SECONDS).entry().observation());
                var cancellation = pool.submit(() -> assertThrows(ConflictException.class,
                        () -> cancel.execute(f.requestId(), caller("alice"))));
                assertEquals("execution-already-started", cancellation.get(3, TimeUnit.SECONDS).getCode());
                jdbc.update("DELETE FROM user_roles WHERE user_id=?", operator.id());
            }
            finally {
                release.countDown();
            }
            var denied = assertThrows(ExecutionException.class, () -> running.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ForbiddenException.class, denied.getCause());
            assertEquals(Disposition.AUTHORITY_REVOKED,
                    records.find(command.commandId()).orElseThrow().observation().disposition());
        }
        assertEquals(JobStatus.RECONCILIATION_REQUIRED, job(f).getStatus());
        verify(handler, times(1)).reconcile(any());
    }

    @Test
    void staleVersionsReusedIdsAndMissingReasonsCannotMutateExecution() {
        var f = held();
        var stale = input(f);
        reconcile(f);
        assertEquals("stale-execution-recovery", assertThrows(ConflictException.class,
                () -> recovery.reconcile(f.requestId(), f.jobId(), stale, caller("admin")))
            .getCode());
        var valid = input(f);
        recovery.reconcile(f.requestId(), f.jobId(), valid, caller("admin"));
        assertEquals("recovery-command-conflict",
                assertThrows(ConflictException.class,
                        () -> recovery.reconcile(f.requestId(), f.jobId(),
                                new Input(valid.commandId(), valid.expectedVersion(), "Different"), caller("admin")))
                    .getCode());
        assertThrows(RequestValidationException.class, () -> recovery.reconcile(f.requestId(), f.jobId(),
                new Input(UUID.randomUUID(), request(f).getVersion(), " "), caller("admin")));
        var other = held();
        assertThrows(NotFoundException.class,
                () -> recovery.reconcile(f.requestId(), other.jobId(), input(f), caller("admin")));
    }

    private void auditFault(Fixture f, String action, Runnable body) {
        String fn = "recovery_fault_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + fn
                + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'recovery audit failure' USING ERRCODE='23514'; END; $$");
        jdbc.execute(
                "CREATE TRIGGER " + fn + " BEFORE INSERT ON lifecycle_audit_events FOR EACH ROW WHEN (NEW.request_id="
                        + f.requestId() + " AND NEW.action='" + action + "') EXECUTE FUNCTION " + fn + "()");
        try {
            body.run();
        }
        finally {
            jdbc.execute("DROP TRIGGER " + fn + " ON lifecycle_audit_events");
            jdbc.execute("DROP FUNCTION " + fn + "()");
        }
    }

    @Test
    void auditFailuresRollBackStartOutcomeAndRetryAuthorization() {
        var f = held();
        var command = input(f);
        auditFault(f, "RECONCILIATION_STARTED", () -> assertThrows(org.springframework.dao.DataAccessException.class,
                () -> recovery.reconcile(f.requestId(), f.jobId(), command, caller("admin"))));
        assertTrue(records.find(command.commandId()).isEmpty());
        verify(handler, never()).reconcile(any());
        when(handler.reconcile(any())).thenReturn(proof(SUCCEEDED));
        auditFault(f, "RECONCILIATION_COMPLETED", () -> assertThrows(org.springframework.dao.DataAccessException.class,
                () -> recovery.reconcile(f.requestId(), f.jobId(), command, caller("admin"))));
        assertEquals(JobStatus.RECONCILIATION_REQUIRED, job(f).getStatus());
        assertNull(records.find(command.commandId()).orElseThrow().observation());
        recovery.reconcile(f.requestId(), f.jobId(), command, caller("admin"));
        verify(handler, times(1)).reconcile(any());
        when(clock.instant()).thenReturn(now.plusSeconds(61));
        when(handler.reconcile(any())).thenReturn(ProviderObservation.unknown());
        reconcile(f);
        var retry = input(f);
        auditFault(f, "RETRY_AUTHORIZED", () -> assertThrows(org.springframework.dao.DataAccessException.class,
                () -> recovery.retry(f.requestId(), f.jobId(), retry, caller("admin"))));
        assertTrue(records.find(retry.commandId()).isEmpty());
        assertEquals(JobStatus.RECONCILIATION_REQUIRED, job(f).getStatus());
    }

    @Test
    void recoveryHistoryIsImmutableAndApiHidesInternalClaimTokens() throws Exception {
        var f = held();
        var command = input(f);
        var principal = new UserPrincipal(caller("admin").id(), "admin", Set.of("ADMIN"), null);
        var auth = new org.springframework.security.authentication.TestingAuthenticationToken(principal, null,
                "ROLE_TEST");
        String path = "/api/v1/requests/" + f.requestId() + "/execution-recovery";
        String body = "{\"commandId\":\"" + command.commandId() + "\",\"expectedVersion\":" + command.expectedVersion()
                + ",\"reason\":\"Investigated\"}";
        mvc.perform(post(path + "/" + f.jobId() + "/reconcile").with(authentication(auth))
            .contentType("application/json")
            .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.command.disposition").value("OBSERVED"))
            .andExpect(jsonPath("$.command.claimToken").doesNotExist());
        mvc.perform(get(path).with(authentication(auth)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.commands[0].provider.outcome").value("UNKNOWN"))
            .andExpect(jsonPath("$.jobs[0].claimToken").doesNotExist());
        mvc.perform(post(path + "/" + f.jobId() + "/retry").with(authentication(auth))
            .contentType("application/json")
            .content("{}")).andExpect(status().isBadRequest());
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc
            .update("UPDATE execution_recovery_commands SET reason='changed' WHERE command_id=?", command.commandId()));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc
            .update("DELETE FROM execution_recovery_observations WHERE command_id=?", command.commandId()));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.execute("TRUNCATE execution_recovery_commands CASCADE"));
    }

    @Test
    void legacySuccessRecordsInferredEvidenceWithoutInventingInputPlanOrSafeCancellation() {
        Long requestId = jdbc
            .queryForObject("INSERT INTO requests(type_id,requester_id,status,payload,execution_start_unknown) "
                    + "SELECT t.id,u.id,'APPROVED','{}'::jsonb,true FROM request_types t,users u WHERE t.code='EXPENSE' AND u.username='alice' RETURNING id",
                    Long.class);
        Long jobId = jdbc.queryForObject(
                "INSERT INTO execution_jobs(request_id,idempotency_key,action,status) VALUES (?,?,'ACCOUNTING_EXPORT','RECONCILIATION_REQUIRED') RETURNING id",
                Long.class, requestId, "legacy-recovery:" + requestId);
        var f = new Fixture(requestId, jobId);
        reconcile(f);
        assertEquals("execution-state-unknown", assertThrows(ConflictException.class, () -> retry(f)).getCode());
        when(handler.reconcile(any())).thenReturn(proof(SUCCEEDED));
        reconcile(f);
        assertEquals(JobStatus.COMPLETED, job(f).getStatus());
        assertTrue(job(f).isStartInferred());
        assertEquals(1, job(f).getAttemptCount());
        assertNull(job(f).getInputPayload());
        assertNull(request(f).getExecutionPlannedAt());
        assertEquals(RequestStatus.APPROVED, request(f).getStatus());
        assertTrue(request(f).isExecutionStartUnknown());
        assertTrue(request(f).isExecutionStartInferred());
        assertNotNull(request(f).getExecutionStartedAt());
        assertThrows(ConflictException.class, () -> cancel.execute(requestId, caller("alice")));
        verify(handler, never()).execute(any());
    }

}
