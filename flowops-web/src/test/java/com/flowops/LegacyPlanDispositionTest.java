package com.flowops;

import com.flowops.application.execution.*;
import com.flowops.application.execution.model.LegacyPlanDisposition.*;
import com.flowops.application.execution.model.ExecutionAttempt.Baseline;
import com.flowops.application.execution.port.*;
import com.flowops.application.mediator.Mediator;
import com.flowops.application.request.lifecycle.CancelRequest;
import com.flowops.application.request.model.ExecutionProgress;
import com.flowops.application.request.query.GetRequest;
import com.flowops.domain.exception.*;
import com.flowops.domain.execution.*;
import com.flowops.domain.execution.port.*;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.port.UserRepository;
import com.flowops.domain.organization.port.DepartmentRepository;
import com.flowops.domain.request.*;
import com.flowops.domain.request.port.RequestRepository;
import com.flowops.domain.workflow.RequestType;
import com.flowops.domain.workflow.port.RequestTypeRepository;
import com.flowops.security.UserPrincipal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class LegacyPlanDispositionTest extends AbstractIntegrationTest {

    @MockitoBean
    ActionHandler handler;

    @MockitoBean
    Clock clock;

    @Autowired
    DisposeLegacyExecutionPlan disposition;

    @Autowired
    LegacyPlanDispositionRepository records;

    @Autowired
    ExecutionAttemptRepository attempts;

    @Autowired
    ExecutionRecoveryTransactions recoveryTx;

    @Autowired
    RecoverExecution recovery;

    @Autowired
    ProviderContractRepository providerHistory;

    @Autowired
    StartExecutionJob start;

    @Autowired
    FinishExecutionJob finish;

    @Autowired
    HeartbeatExecutionJob heartbeat;

    @Autowired
    CancelRequest cancel;

    @Autowired
    RequestRepository requests;

    @Autowired
    ExecutionJobRepository jobs;

    @Autowired
    ExecutionPlanRepository plans;

    @Autowired
    RequestTypeRepository types;

    @Autowired
    DepartmentRepository departments;

    @Autowired
    UserRepository users;

    @Autowired
    Mediator mediator;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    private final Instant now = Instant.parse("2026-12-11T12:00:00Z"), old = now.minusSeconds(86400);

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactions);
    }

    private Request request(Long id) {
        return requests.findById(id).orElseThrow();
    }

    private CallerIdentity caller(String name) {
        var u = users.findByUsername(name).orElseThrow();
        return new CallerIdentity(u.getId(), u.getUsername());
    }

    private List<ExecutionJob> rows(Long id) {
        return jobs.findByRequestIdOrderByCreatedAtAsc(id);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor auth(CallerIdentity c) {
        return authentication(new org.springframework.security.authentication.TestingAuthenticationToken(
                new UserPrincipal(c.id(), c.username(), Set.of("ADMIN"), null), null, "ROLE_ADMIN"));
    }

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(now);
        when(handler.supportsIdempotentRetries()).thenReturn(true);
        TestProviderContracts.stub(handler);
        when(handler.reconcile(any())).thenReturn(ProviderObservation.unknown());
    }

    private Request legacy() {
        return legacy(RequestStatus.APPROVED);
    }

    private Request legacy(RequestStatus status) {
        String code = "LEG_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
        var type = tx().execute(s -> {
            var t = new RequestType(code, "Historical", null, departments.findByCode("FINANCE").orElseThrow());
            t.setExecutionActions(List.of(JobActions.PURCHASE_TICKET));
            return types.save(t);
        });
        long id = jdbc.queryForObject(
                "INSERT INTO requests(type_id,requester_id,status,payload,execution_planned_at,execution_start_unknown) VALUES (?,?,?,'{\"amount\":41}'::jsonb,?,true) RETURNING id",
                Long.class, type.getId(), caller("alice").id(), status.name(), Timestamp.from(old));
        return request(id);
    }

    private ExecutionJob job(Request r, String action, JobStatus status, boolean input) {
        boolean started = Set.of(JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.DEAD_LETTER, JobStatus.STARTED)
            .contains(status);
        return tx().execute(s -> {
            if (started)
                jdbc.update(
                        "UPDATE requests SET execution_started_at=?,execution_start_inferred=true,execution_start_unknown=false WHERE id=?",
                        Timestamp.from(old.plusSeconds(1)), r.getId());
            var token = status == JobStatus.STARTED ? UUID.randomUUID() : null;
            Long id = jdbc.queryForObject(
                    "INSERT INTO execution_jobs(request_id,idempotency_key,action,status,input_payload,created_at,started_at,start_inferred,attempt_count,retry_count,completed_at,claim_token,lease_expires_at) VALUES (?,?,?,?,?::jsonb,?,?,?, ?,?,?,?,?) RETURNING id",
                    Long.class, r.getId(), "original:" + r.getId() + ":" + action, action, status.name(),
                    input ? "{\"amount\":41}" : null, Timestamp.from(old),
                    started ? Timestamp.from(old.plusSeconds(1)) : null, started, started ? 1 : 0,
                    status == JobStatus.FAILED || status == JobStatus.DEAD_LETTER ? 1 : 0,
                    status == JobStatus.COMPLETED ? Timestamp.from(old.plusSeconds(2)) : null, token,
                    status == JobStatus.STARTED ? Timestamp.from(now.plusSeconds(100)) : null);
            var j = jobs.findById(id).orElseThrow();
            if (started)
                attempts
                    .observeBaseline(new Baseline(id, r.getId(), old.plusSeconds(3), status.name(), j.getAttemptCount(),
                            j.getStartedAt(), j.isStartInferred(), j.getClaimToken(), j.getLeaseExpiresAt()));
            return j;
        });
    }

    private ProposedAction existing(ExecutionJob j) {
        return new ProposedAction(j.getAction(), j.getId(), null, null,
                j.getStatus() == JobStatus.COMPLETED ? null : "TEST_PROVIDER");
    }

    private Input input(Request r, Mode mode, List<ProposedAction> actions) {
        return new Input(UUID.randomUUID(), request(r.getId()).getVersion(), mode,
                "Reviewed historical authorization and complete intended action set", "archive:approval-review-123",
                actions);
    }

    private Result adopt(Request r, List<ProposedAction> actions) {
        return disposition.dispose(r.getId(), input(r, Mode.ADOPT, actions), caller("admin"));
    }

    private ExecutionProgress progress(Request r) {
        return mediator.send(new GetRequest(r.getId(), caller("alice"))).request().executionProgress();
    }

    private int facts(Request r, String type, String action) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM lifecycle_audit_events WHERE request_id=? AND event_type=? AND action=?",
                Integer.class, r.getId(), type, action);
    }

    private com.flowops.application.execution.ExecutionRecoveryTransactions.Input recoveryInput(Request r) {
        return new com.flowops.application.execution.ExecutionRecoveryTransactions.Input(UUID.randomUUID(),
                request(r.getId()).getVersion(), "Provider evidence reviewed");
    }

    private ProviderObservation proof(ProviderObservation.Outcome outcome) {
        return new ProviderObservation(outcome, "provider:historical-key-proof", Map.of("ok", true));
    }

    @Test
    void adoptionPreservesOriginalIdentityAndHistoryAndDoesNotReapproveOrExecute() {
        var r = legacy();
        var j = job(r, JobActions.ACCOUNTING_EXPORT, JobStatus.PLANNED, true);
        var command = input(r, Mode.ADOPT, List.of(existing(j)));
        var result = disposition.dispose(r.getId(), command, caller("admin"));
        var stored = request(r.getId());
        var adopted = plans.findByRequestId(r.getId()).orElseThrow();
        var held = rows(r.getId()).getFirst();
        assertEquals(result.disposition().planId(), stored.getExecutionPlanId());
        assertEquals(command.commandId(), stored.getLegacyPlanDispositionId());
        assertTrue(stored.isExecutionStartUnknown());
        assertFalse(stored.hasUnresolvedExecutionHistory());
        assertNull(stored.getApprovedAt());
        assertNull(stored.getExecutionStartedAt());
        assertNull(stored.getOutcome());
        assertEquals(old, adopted.plannedAt());
        assertNull(adopted.sourceTypeUpdatedAt());
        assertEquals(ApprovalEvidence.Trigger.LEGACY_PLAN_DISPOSITION, adopted.approval().trigger());
        assertEquals(ApprovalEvidence.ActorKind.UNKNOWN, adopted.approval().actorKind());
        assertNull(adopted.approval().actorId());
        assertEquals("admin", adopted.approval().initiatedByName());
        assertEquals(j.getId(), held.getId());
        assertEquals(j.getCreatedAt(), held.getCreatedAt());
        assertEquals(j.getIdempotencyKey(), held.getIdempotencyKey());
        assertEquals(j.getInputPayload(), held.getInputPayload());
        assertEquals(0, held.getAttemptCount());
        assertEquals(0, held.getRetryCount());
        assertEquals(JobStatus.RECONCILIATION_REQUIRED, held.getStatus());
        assertTrue(plans.jobsMatch(adopted.id()));
        assertEquals("TEST_PROVIDER", providerHistory.firstProvider(j.getId()).orElseThrow());
        assertEquals(0, facts(r, "APPROVAL", "FINALIZED"));
        assertEquals(1, facts(r, "EXECUTION_PLAN", "LEGACY_ADOPTED"));
        assertFalse(
                progress(r).attentionReasons().contains(ExecutionProgress.AttentionReason.EXECUTION_HISTORY_UNKNOWN));
        assertTrue(progress(r).requiresAttention());
        assertTrue(start.start(new ExecutionJobRepository.Candidate(j.getId(), r.getId())).isEmpty());
        assertThrows(ConflictException.class, () -> cancel.execute(r.getId(), caller("alice")));
        assertThrows(IllegalArgumentException.class,
                () -> new com.flowops.domain.request.event.RequestApproved(r.getId(), adopted.id(),
                        adopted.approval()));
        verify(handler, never()).execute(any());
        verify(handler, never()).reconcile(any());
    }

    @Test
    void reviewedMissingActionIsHeldAndProviderSuccessCompletesTheExactManifest() {
        var r = legacy();
        var completed = job(r, JobActions.ACCOUNTING_EXPORT, JobStatus.COMPLETED, true);
        var newAction = new ProposedAction(JobActions.PROVISIONING, null, "archived-key:" + r.getId(),
                Map.of("resource", "reviewed-resource"), "TEST_PROVIDER");
        adopt(r, List.of(existing(completed), newAction));
        var created = rows(r.getId()).stream()
            .filter(j -> !j.getId().equals(completed.getId()))
            .findFirst()
            .orElseThrow();
        assertEquals(newAction.idempotencyKey(), created.getIdempotencyKey());
        assertEquals(newAction.input(), created.getInputPayload());
        assertEquals(JobStatus.RECONCILIATION_REQUIRED, created.getStatus());
        assertEquals(1, progress(r).jobs().completed());
        assertEquals(2, progress(r).expectedJobs());
        when(handler.reconcile(any())).thenReturn(proof(ProviderObservation.Outcome.SUCCEEDED));
        recovery.reconcile(r.getId(), created.getId(), recoveryInput(r), caller("admin"));
        assertEquals(RequestStatus.EXECUTED, request(r.getId()).getStatus());
        assertEquals(RequestOutcome.EXECUTED, request(r.getId()).getOutcome());
        assertEquals(now, request(r.getId()).getExecutedAt());
        assertEquals(old.plusSeconds(1), request(r.getId()).getExecutionStartedAt());
        assertNull(request(r.getId()).getApprovedAt());
        assertEquals(1, facts(r, "EXECUTION", "COMPLETED"));
        assertFalse(progress(r).requiresAttention());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE idempotency_key LIKE ?", Integer.class,
                "req:" + r.getId() + ":APPROVED:%"));
        verify(handler, never()).execute(any());
    }

    @Test
    void explicitEmptyPlanCompletesWithoutInventingApprovalOrExecutionStart() {
        var r = legacy();
        var result = adopt(r, List.of());
        assertEquals(RequestStatus.EXECUTED, request(r.getId()).getStatus());
        assertEquals(0, plans.findByRequestId(r.getId()).orElseThrow().actions().size());
        assertEquals(0, progress(r).expectedJobs());
        assertNull(request(r.getId()).getApprovedAt());
        assertNull(request(r.getId()).getExecutionStartedAt());
        assertTrue(request(r.getId()).isExecutionStartUnknown());
        assertEquals(now, result.disposition().recordedAt());
        assertEquals(old, request(r.getId()).getExecutionPlannedAt());
        assertEquals(1, facts(r, "EXECUTION", "COMPLETED"));
        verify(handler, never()).execute(any());
        verify(handler, never()).reconcile(any());
    }

    @Test
    void quarantineRecordsAnExplicitDispositionWithoutInventingUnknownInputOrAPlan() {
        var r = legacy();
        var j = job(r, JobActions.ACCOUNTING_EXPORT, JobStatus.RECONCILIATION_REQUIRED, false);
        var bad = input(r, Mode.ADOPT, List.of(existing(j)));
        assertEquals("legacy-job-input-unknown",
                assertThrows(ConflictException.class, () -> disposition.dispose(r.getId(), bad, caller("admin")))
                    .getCode());
        assertTrue(records.find(bad.commandId()).isEmpty());
        var command = input(r, Mode.KEEP_QUARANTINED, List.of());
        var result = disposition.dispose(r.getId(), command, caller("admin"));
        long version = request(r.getId()).getVersion();
        assertNull(result.disposition().planId());
        assertTrue(plans.findByRequestId(r.getId()).isEmpty());
        assertNull(rows(r.getId()).getFirst().getInputPayload());
        assertTrue(progress(r).requiresAttention());
        assertEquals(result, disposition.dispose(r.getId(), command, caller("admin")));
        assertEquals(version, request(r.getId()).getVersion());
        assertEquals(1, facts(r, "EXECUTION_PLAN", "LEGACY_QUARANTINED"));
    }

    @Test
    void unknownProviderOutcomeCannotAuthorizeTheFirstKnownLegacyAttemptEvenWithIdempotency() {
        var r = legacy();
        var j = job(r, JobActions.ACCOUNTING_EXPORT, JobStatus.RECONCILIATION_REQUIRED, true);
        adopt(r, List.of(existing(j)));
        recovery.reconcile(r.getId(), j.getId(), recoveryInput(r), caller("admin"));
        assertEquals("execution-retry-unsafe", assertThrows(ConflictException.class,
                () -> recovery.retry(r.getId(), j.getId(), recoveryInput(r), caller("admin")))
            .getCode());
        when(handler.reconcile(any())).thenReturn(proof(ProviderObservation.Outcome.SAFE_TO_RETRY));
        recovery.reconcile(r.getId(), j.getId(), recoveryInput(r), caller("admin"));
        recovery.retry(r.getId(), j.getId(), recoveryInput(r), caller("admin"));
        var queued = rows(r.getId()).getFirst();
        assertEquals(JobStatus.PLANNED, queued.getStatus());
        assertEquals(0, queued.getAttemptCount());
        assertNull(queued.getStartedAt());
        assertNull(request(r.getId()).getExecutionStartedAt());
        assertTrue(request(r.getId()).isExecutionStartUnknown());
        var claim = start.start(new ExecutionJobRepository.Candidate(j.getId(), r.getId())).orElseThrow();
        assertEquals(1, claim.job().getAttemptCount());
        assertEquals(j.getInputPayload(), claim.command().payload());
        assertEquals(j.getIdempotencyKey(), claim.command().idempotencyKey());
        assertEquals(now, request(r.getId()).getExecutionStartedAt());
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        assertEquals(HeartbeatExecutionJob.Renewal.RENEWED,
                heartbeat.renew(new ExecutionLease(r.getId(), j.getId(), 1, claim.token())));
        assertTrue(finish.finish(claim, Map.of("ok", true), null, true));
        assertEquals(RequestStatus.EXECUTED, request(r.getId()).getStatus());
        assertFalse(progress(r).requiresAttention());
        verify(handler, never()).execute(any());
    }

    @Test
    void adoptionOfAttemptedWorkPreservesCountersAndRequiresNormalProviderRecovery() {
        var r = legacy();
        var j = job(r, JobActions.ACCOUNTING_EXPORT, JobStatus.FAILED, true);
        adopt(r, List.of(existing(j)));
        var held = rows(r.getId()).getFirst();
        assertEquals(1, held.getAttemptCount());
        assertEquals(1, held.getRetryCount());
        assertEquals(j.getStartedAt(), held.getStartedAt());
        recovery.reconcile(r.getId(), j.getId(), recoveryInput(r), caller("admin"));
        recovery.retry(r.getId(), j.getId(), recoveryInput(r), caller("admin"));
        var claim = start.start(new ExecutionJobRepository.Candidate(j.getId(), r.getId())).orElseThrow();
        assertEquals(2, claim.job().getAttemptCount());
        assertEquals(old.plusSeconds(1), request(r.getId()).getExecutionStartedAt());
        assertTrue(finish.finish(claim, Map.of(), null, true));
    }

    @Test
    void omissionsForeignJobsReplacementInputsAndConflictingKeysAreRejectedAtomically() {
        var r = legacy();
        var a = job(r, JobActions.ACCOUNTING_EXPORT, JobStatus.PLANNED, true);
        var b = job(r, JobActions.PROVISIONING, JobStatus.RECONCILIATION_REQUIRED, true);
        assertEquals("legacy-plan-incomplete",
                assertThrows(ConflictException.class, () -> adopt(r, List.of(existing(a)))).getCode());
        assertEquals("legacy-job-identity",
                assertThrows(ConflictException.class, () -> adopt(r, List.of(
                        new ProposedAction(a.getAction(), a.getId(), a.getIdempotencyKey(), Map.of(), "TEST_PROVIDER"),
                        existing(b))))
                    .getCode());
        var other = legacy();
        var foreign = job(other, JobActions.ACCOUNTING_EXPORT, JobStatus.PLANNED, true);
        assertEquals("legacy-job-identity",
                assertThrows(ConflictException.class, () -> adopt(r, List.of(existing(foreign), existing(b))))
                    .getCode());
        assertEquals("legacy-action-history",
                assertThrows(ConflictException.class,
                        () -> adopt(r, List.of(existing(a), existing(b), new ProposedAction(JobActions.PURCHASE_TICKET,
                                null, foreign.getIdempotencyKey(), Map.of(), "TEST_PROVIDER"))))
                    .getCode());
        assertEquals(0, disposition.history(r.getId(), 50, caller("admin")).size());
        assertTrue(plans.findByRequestId(r.getId()).isEmpty());
        assertEquals(JobStatus.PLANNED, jobs.findById(a.getId()).orElseThrow().getStatus());
    }

    @Test
    void activeClaimsAndPendingProviderLookupsPreventAdoption() {
        var r = legacy();
        var j = job(r, JobActions.ACCOUNTING_EXPORT, JobStatus.STARTED, true);
        assertEquals("execution-attempt-active",
                assertThrows(ConflictException.class, () -> adopt(r, List.of(existing(j)))).getCode());
        when(clock.instant()).thenReturn(now.plusSeconds(101));
        var prepared = recoveryTx.begin(r.getId(), j.getId(), recoveryInput(r), caller("admin"));
        assertEquals("reconciliation-in-progress",
                assertThrows(ConflictException.class, () -> adopt(r, List.of(existing(j)))).getCode());
        recoveryTx.finish(prepared.result().entry().command().id(), ProviderObservation.unknown(), null);
        adopt(r, List.of(existing(j)));
        assertEquals(JobStatus.RECONCILIATION_REQUIRED, rows(r.getId()).getFirst().getStatus());
    }

    @Test
    void missingHistoricalRowsCannotBeReplacedByAnEmptyPlanOrANewIdentity() {
        var r = legacy();
        var j = job(r, JobActions.ACCOUNTING_EXPORT, JobStatus.COMPLETED, true);
        // Controlled administrative-loss fixture; application recovery never disables
        // guards.
        tx().executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role=replica");
            jdbc.update("DELETE FROM execution_jobs WHERE id=?", j.getId());
            jdbc.execute("SET LOCAL session_replication_role=origin");
        });
        try {
            assertEquals("legacy-job-missing",
                    assertThrows(ConflictException.class, () -> adopt(r, List.of())).getCode());
            assertTrue(plans.findByRequestId(r.getId()).isEmpty());
        }
        finally {
            tx().executeWithoutResult(s -> {
                jdbc.execute("SET LOCAL session_replication_role=replica");
                jdbc.update(
                        "INSERT INTO execution_jobs(id,request_id,idempotency_key,action,status,input_payload,created_at,started_at,start_inferred,attempt_count,completed_at) OVERRIDING SYSTEM VALUE VALUES (?,?,?,?,'COMPLETED','{\"amount\":41}'::jsonb,?,?,true,1,?)",
                        j.getId(), r.getId(), j.getIdempotencyKey(), j.getAction(), Timestamp.from(old),
                        Timestamp.from(old.plusSeconds(1)), Timestamp.from(old.plusSeconds(2)));
                jdbc.execute("SET LOCAL session_replication_role=origin");
            });
        }
    }

    @Test
    void replayIsStableAfterCompletionAndConflictingCommandArgumentsAreRejected() {
        when(clock.instant()).thenReturn(now.plusNanos(123456789));
        var r = legacy();
        var command = input(r, Mode.ADOPT, List.of());
        var first = disposition.dispose(r.getId(), command, caller("admin"));
        long version = request(r.getId()).getVersion();
        assertEquals(first, disposition.dispose(r.getId(), command, caller("admin")));
        assertEquals(version, request(r.getId()).getVersion());
        assertEquals(1, facts(r, "EXECUTION", "COMPLETED"));
        var conflict = new Input(command.commandId(), command.expectedVersion(), Mode.ADOPT, "Different reason",
                command.evidenceReference(), command.actions());
        assertEquals("plan-disposition-command-conflict",
                assertThrows(ConflictException.class, () -> disposition.dispose(r.getId(), conflict, caller("admin")))
                    .getCode());
        assertThrows(ConflictException.class, () -> adopt(r, List.of()));
    }

    private CallerIdentity operator(String scope) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Long user = jdbc.queryForObject(
                "INSERT INTO users(username,display_name,password_hash) VALUES (?,?,'unused') RETURNING id", Long.class,
                "legacy_" + suffix, "Legacy operator");
        Long role = jdbc.queryForObject("INSERT INTO roles(code,name) VALUES (?,?) RETURNING id", Long.class,
                "LD_" + suffix.substring(0, 24), "Legacy disposition");
        jdbc.update("INSERT INTO user_roles VALUES (?,?)", user, role);
        for (String permission : List.of("execution:read", "execution:plan:dispose")) {
            Long grant = jdbc.queryForObject(
                    "INSERT INTO permissions(code,name,scope_type,scope_value) VALUES (?,?,'REQUEST_TYPE',?) RETURNING id",
                    Long.class, permission + ":" + suffix, permission, scope);
            jdbc.update("INSERT INTO role_permissions VALUES (?,?)", role, grant);
        }
        return new CallerIdentity(user, "legacy_" + suffix);
    }

    @Test
    void scopedCurrentAuthorityAndSeparationOfDutiesApplyEvenToReplays() throws Exception {
        var r = legacy();
        var operator = operator(r.getType().getCode());
        var other = legacy();
        assertThrows(ForbiddenException.class,
                () -> disposition.dispose(other.getId(), input(other, Mode.ADOPT, List.of()), operator));
        var command = input(r, Mode.ADOPT, List.of());
        mvc.perform(post("/api/v1/requests/" + r.getId() + "/legacy-plan-dispositions").with(auth(operator))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(command)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.disposition.mode").value("ADOPT"));
        mvc.perform(get("/api/v1/requests/" + r.getId() + "/legacy-plan-dispositions").with(auth(operator)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].evidenceReference").value(command.evidenceReference()))
            .andExpect(jsonPath("$[0].payload").doesNotExist());
        jdbc.update("DELETE FROM user_roles WHERE user_id=?", operator.id());
        assertThrows(ForbiddenException.class, () -> disposition.dispose(r.getId(), command, operator));
        var own = legacy();
        var self = operator(own.getType().getCode());
        jdbc.update("UPDATE requests SET requester_id=? WHERE id=?", self.id(), own.getId());
        assertThrows(ForbiddenException.class,
                () -> disposition.dispose(own.getId(), input(own, Mode.ADOPT, List.of()), self));
        mvc.perform(get("/api/v1/requests/" + other.getId() + "/legacy-plan-dispositions").with(auth(caller("alice"))))
            .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = "APPROVED", mode = EnumSource.Mode.EXCLUDE)
    void onlyUnplannedHistoricalApprovalsMayBeDisposed(RequestStatus status) {
        var r = legacy(status);
        assertEquals("legacy-plan-state", assertThrows(ConflictException.class, () -> adopt(r, List.of())).getCode());
    }

    @Test
    void immutableDispositionProviderAndRootIdentitiesCannotBeChanged() {
        var r = legacy();
        var j = job(r, JobActions.ACCOUNTING_EXPORT, JobStatus.RECONCILIATION_REQUIRED, true);
        var result = adopt(r, List.of(existing(j)));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc
            .update("DELETE FROM legacy_plan_dispositions WHERE command_id=?", result.disposition().commandId()));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("UPDATE legacy_plan_dispositions SET evidence_reference='changed' WHERE command_id=?",
                        result.disposition().commandId()));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc
            .update("UPDATE legacy_plan_provider_bindings SET provider_id='changed' WHERE job_id=?", j.getId()));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("UPDATE requests SET legacy_plan_disposition_id=NULL WHERE id=?", r.getId()));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("UPDATE requests SET execution_start_unknown=false WHERE id=?", r.getId()));
        assertEquals(old, request(r.getId()).getExecutionPlannedAt());
    }

    @Test
    void anAuditFailureRollsBackTheManifestHoldsReceiptsBindingsAndReservation() {
        var r = legacy();
        var j = job(r, JobActions.ACCOUNTING_EXPORT, JobStatus.PLANNED, true);
        var command = input(r, Mode.ADOPT, List.of(existing(j)));
        long version = request(r.getId()).getVersion();
        String name = "legacy_fault_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + name
                + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'legacy audit fault' USING ERRCODE='23514'; END; $$");
        jdbc.execute(
                "CREATE TRIGGER " + name + " BEFORE INSERT ON lifecycle_audit_events FOR EACH ROW WHEN (NEW.request_id="
                        + r.getId() + " AND NEW.action='LEGACY_ADOPTED') EXECUTE FUNCTION " + name + "()");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> disposition.dispose(r.getId(), command, caller("admin")));
        }
        finally {
            jdbc.execute("DROP TRIGGER " + name + " ON lifecycle_audit_events");
            jdbc.execute("DROP FUNCTION " + name + "()");
        }
        assertEquals(version, request(r.getId()).getVersion());
        assertNull(request(r.getId()).getLegacyPlanDispositionId());
        assertTrue(plans.findByRequestId(r.getId()).isEmpty());
        assertTrue(records.find(command.commandId()).isEmpty());
        assertEquals(JobStatus.PLANNED, rows(r.getId()).getFirst().getStatus());
        assertTrue(attempts.baseline(j.getId()).isEmpty());
        disposition.dispose(r.getId(), command, caller("admin"));
        assertTrue(plans.findByRequestId(r.getId()).isPresent());
    }

    @Test
    void competingDispositionsCommitOnePlanAndOneCommand() throws Exception {
        var r = legacy();
        var first = input(r, Mode.ADOPT, List.of());
        var second = input(r, Mode.ADOPT, List.of());
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = new ArrayList<Future<Boolean>>();
            for (var command : List.of(first, second))
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        disposition.dispose(r.getId(), command, caller("admin"));
                        return true;
                    }
                    catch (ConflictException expected) {
                        return false;
                    }
                }));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            int success = 0;
            for (var result : results)
                if (result.get(10, TimeUnit.SECONDS))
                    success++;
            assertEquals(1, success);
        }
        assertEquals(1, disposition.history(r.getId(), 50, caller("admin")).size());
        assertEquals(1, facts(r, "EXECUTION", "COMPLETED"));
    }

    @Test
    void staleVersionsMalformedActionsAndWrongProviderDeclarationsFailWithoutWrites() throws Exception {
        var r = legacy();
        var j = job(r, JobActions.ACCOUNTING_EXPORT, JobStatus.RECONCILIATION_REQUIRED, true);
        var command = input(r, Mode.ADOPT, List.of(existing(j)));
        var stale = new Input(command.commandId(), command.expectedVersion() + 1, command.mode(), command.reason(),
                command.evidenceReference(), command.actions());
        assertEquals("stale-plan-disposition",
                assertThrows(ConflictException.class, () -> disposition.dispose(r.getId(), stale, caller("admin")))
                    .getCode());
        assertThrows(RequestValidationException.class, () -> adopt(r, List.of(existing(j), existing(j))));
        assertThrows(RequestValidationException.class, () -> disposition.dispose(r.getId(),
                new Input(UUID.randomUUID(), r.getVersion(), Mode.ADOPT, "Reason", "", List.of()), caller("admin")));
        assertEquals("legacy-provider-identity", assertThrows(ConflictException.class,
                () -> adopt(r, List.of(new ProposedAction(j.getAction(), j.getId(), null, null, "OTHER_PROVIDER"))))
            .getCode());
        mvc.perform(post("/api/v1/requests/" + r.getId() + "/legacy-plan-dispositions").with(auth(caller("admin")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"commandId\":\"" + UUID.randomUUID()
                    + "\",\"expectedVersion\":0,\"mode\":\"ADOPT\",\"reason\":\"Review\",\"evidenceReference\":\"archive:1\",\"actions\":[null]}"))
            .andExpect(status().isBadRequest());
        assertTrue(records.find(command.commandId()).isEmpty());
        assertTrue(plans.findByRequestId(r.getId()).isEmpty());
    }

    @Test
    void replayUsesJsonValueEqualityAndLaterProviderRouteChangesRemainBlocked() {
        var r = legacy();
        var action = new ProposedAction(JobActions.ACCOUNTING_EXPORT, null, "reviewed-key:" + r.getId(),
                Map.of("amount", 41), "TEST_PROVIDER");
        var command = input(r, Mode.ADOPT, List.of(action));
        var first = disposition.dispose(r.getId(), command, caller("admin"));
        var equivalent = new Input(command.commandId(), command.expectedVersion(), command.mode(), command.reason(),
                command.evidenceReference(), List.of(new ProposedAction(action.action(), null, action.idempotencyKey(),
                        Map.of("amount", 41L), "TEST_PROVIDER")));
        assertEquals(first, disposition.dispose(r.getId(), equivalent, caller("admin")));
        doReturn(new ProviderContract("OTHER_PROVIDER", "v1", ProviderContract.Transport.LOCAL, Duration.ZERO,
                Duration.ZERO, Duration.ofSeconds(20), Duration.ofSeconds(10), true, true, false))
            .when(handler)
            .contract(anyString());
        assertEquals("provider-binding-changed", assertThrows(ConflictException.class, () -> recovery
            .reconcile(r.getId(), rows(r.getId()).getFirst().getId(), recoveryInput(r), caller("admin"))).getCode());
        verify(handler, never()).reconcile(any());
        verify(handler, never()).execute(any());
    }

}
