package com.flowops;

import com.flowops.application.request.port.RequestWriteLock;
import com.flowops.application.audit.port.LifecycleAuditRepository;
import com.flowops.application.event.*;
import com.flowops.application.execution.*;
import com.flowops.application.mediator.Mediator;
import com.flowops.application.notification.outbox.port.OutboxMessageRepository;
import com.flowops.application.request.command.*;
import com.flowops.application.request.lifecycle.*;
import com.flowops.application.request.query.GetExecutionPlan;
import com.flowops.domain.exception.*;
import com.flowops.domain.execution.*;
import com.flowops.domain.execution.port.*;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.port.UserRepository;
import com.flowops.domain.request.*;
import com.flowops.domain.request.event.*;
import com.flowops.domain.request.port.*;
import com.flowops.domain.workflow.*;
import com.flowops.domain.workflow.port.*;
import com.flowops.domain.organization.port.DepartmentRepository;
import com.flowops.security.UserPrincipal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

@AutoConfigureMockMvc
@Import(FrozenExecutionPlanTest.Observers.class)
class FrozenExecutionPlanTest extends AbstractIntegrationTest {

    @MockitoBean
    ActionHandler handler;

    @MockitoBean
    Clock clock;

    @Autowired
    Mediator mediator;

    @Autowired
    UserRepository users;

    @Autowired
    RequestRepository requests;

    @Autowired
    RequestWriteLock requestWriteLock;

    @Autowired
    StepInstanceRepository steps;

    @Autowired
    DecisionRepository decisions;

    @Autowired
    ExecutionJobRepository jobs;

    @Autowired
    ExecutionPlanRepository plans;

    @Autowired
    WorkflowRepository workflows;

    @Autowired
    WorkflowStepRepository definitions;

    @Autowired
    RequestTypeRepository types;

    @Autowired
    DepartmentRepository departments;

    @Autowired
    FinalizeApproval finalizer;

    @Autowired
    CompleteRequestExecution completion;

    @Autowired
    ExecutionService materialize;

    @Autowired
    StartExecutionJob start;

    @Autowired
    FinishExecutionJob finish;

    @Autowired
    ProcessExecutionJobs worker;

    @Autowired
    CancelRequest cancel;

    @Autowired
    CloseRequest close;

    @Autowired
    DomainEventPublisher events;

    @Autowired
    LifecycleAuditRepository audit;

    @Autowired
    OutboxMessageRepository outbox;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    com.flowops.application.request.port.ApprovalStore approvals;

    @Autowired
    MockMvc mvc;

    @Autowired
    Probe probe;

    private final Instant now = Instant.parse("2026-12-06T12:00:00Z");

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactions);
    }

    private CallerIdentity caller(String name) {
        var u = users.findByUsername(name).orElseThrow();
        return new CallerIdentity(u.getId(), u.getUsername());
    }

    private Request stored(Long id) {
        return requests.findById(id).orElseThrow();
    }

    private ExecutionPlan plan(Long id) {
        return plans.findByRequestId(id).orElseThrow();
    }

    private int facts(Long id, String type, String action) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM lifecycle_audit_events WHERE request_id=? AND event_type=? AND action=?",
                Integer.class, id, type, action);
    }

    private List<Long> intents(Long id) {
        return tx().execute(s -> outbox.findByKeyPrefix("req:" + id + ":").stream().map(m -> m.getId()).toList());
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

    private RequestType type(List<String> actions, boolean skipped) {
        return tx().execute(s -> {
            String code = "M4_"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(java.util.Locale.ROOT);
            var workflow = workflows.save(new Workflow("WF_" + code, "Frozen approval", null));
            var step = new WorkflowStep(workflow, 1, "REVIEW", "Review", "ADMIN");
            step.configure(CompletionRule.ANY_OF, 1,
                    skipped ? Map.of("field", "amount", "op", ">", "value", 9999) : Map.of(), null, null, false);
            definitions.save(step);
            var type = new RequestType(code, "Plan test", null, departments.findByCode("FINANCE").orElseThrow());
            type.setDefaultWorkflow(workflow);
            type.setExecutionActions(actions);
            return types.save(type);
        });
    }

    private Request draft(RequestType type) {
        return mediator
            .send(new CreateDraftCommand(type.getCode(), Map.of("amount", 100, "description", "Frozen input"),
                    caller("alice")))
            .request();
    }

    private Request submit(Request r) {
        return mediator.send(new SubmitRequestCommand(r.getId(), caller("alice"))).request();
    }

    private Request approve(Request r) {
        return mediator
            .send(new DecideStepCommand(r.getId(), DecisionAction.APPROVE, "Reviewed", null, caller("admin")))
            .request();
    }

    private Request approved(List<String> actions) {
        var r = submit(draft(type(actions, false)));
        return approve(r);
    }

    private StartExecutionJob.Claim claim(ExecutionJob j) {
        return start.start(new ExecutionJobRepository.Candidate(j.getId(), j.getRequestId())).orElseThrow();
    }

    @Test
    void humanApprovalFreezesItsEvidenceInputsAndCompleteActionSet() {
        var r = approved(List.of(JobActions.ACCOUNTING_EXPORT, JobActions.PROVISIONING));
        var p = plan(r.getId());
        assertEquals(RequestStatus.APPROVED, r.getStatus());
        assertEquals(p.id(), r.getExecutionPlanId());
        assertEquals(now, p.plannedAt());
        assertEquals(ApprovalEvidence.ActorKind.HUMAN, p.approval().actorKind());
        assertEquals(caller("admin").id(), p.approval().actorId());
        assertNotNull(p.approval().decisionId());
        assertNotNull(p.approval().stepInstanceId());
        assertEquals(List.of(JobActions.ACCOUNTING_EXPORT, JobActions.PROVISIONING),
                p.actions().stream().map(ExecutionPlan.Action::action).toList());
        assertEquals(r.getPayload(), p.actions().getFirst().input());
        assertTrue(plans.jobsMatch(p.id()));
        assertFalse(plans.allCompleted(p.id()));
        assertEquals(1, facts(r.getId(), "APPROVAL", "FINALIZED"));
        assertEquals(1, probe.approvals(r.getId()));
        assertEquals(stored(r.getId()).getVersion(), r.getVersion());
    }

    @Test
    void allSkippedSubmissionPlansAndExecutesWithoutInventingAVoteOrPendingNotification() {
        var r = submit(draft(type(List.of(JobActions.ACCOUNTING_EXPORT), true)));
        var p = plan(r.getId());
        assertEquals(RequestStatus.APPROVED, r.getStatus());
        assertEquals(ApprovalEvidence.Trigger.ALL_STEPS_SKIPPED, p.approval().trigger());
        assertEquals(ApprovalEvidence.ActorKind.SYSTEM, p.approval().actorKind());
        assertNull(p.approval().actorId());
        assertNull(p.approval().decisionId());
        assertEquals(caller("alice").id(), p.approval().initiatedById());
        for (var step : steps.findByRequestIdOrderById(r.getId())) {
            assertEquals(StepState.SKIPPED, step.getState());
            assertTrue(decisions.findByStepInstanceIdOrderById(step.getId()).isEmpty());
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE idempotency_key LIKE ?", Integer.class,
                "req:" + r.getId() + ":%APPROVER_REQUEST:%"));
        assertEquals("SYSTEM", jdbc.queryForObject(
                "SELECT actor_kind FROM lifecycle_audit_events WHERE request_id=? AND event_type='APPROVAL' AND action='FINALIZED'",
                String.class, r.getId()));
        worker.execute();
        assertEquals(RequestStatus.EXECUTED, stored(r.getId()).getStatus());
        assertEquals(1, probe.completed(r.getId()));
        assertEquals(1, probe.approvals(r.getId()));
        assertEquals(1, facts(r.getId(), "EXECUTION", "COMPLETED"));
    }

    @Test
    void allSkippedEmptyPlanCompletesImmediatelyAndCanBeClosed() {
        var r = submit(draft(type(List.of(), true)));
        var p = plan(r.getId());
        assertEquals(RequestStatus.EXECUTED, r.getStatus());
        assertNotNull(r.getExecutionPlanId());
        assertTrue(p.actions().isEmpty());
        assertTrue(plans.allCompleted(p.id()));
        assertTrue(jobs.findByRequestIdOrderByCreatedAtAsc(r.getId()).isEmpty());
        assertNull(r.getExecutionStartedAt());
        assertEquals(1, probe.approvals(r.getId()));
        assertEquals(1, probe.completed(r.getId()));
        assertEquals(1, facts(r.getId(), "APPROVAL", "FINALIZED"));
        assertEquals(1, facts(r.getId(), "EXECUTION", "COMPLETED"));
        assertThrows(ConflictException.class, () -> cancel.execute(r.getId(), caller("alice")));
        assertEquals(RequestStatus.CLOSED, close.execute(r.getId(), caller("alice")).getStatus());
        assertEquals(p, plan(r.getId()));
        verify(handler, never()).execute(argThat(c -> c != null && c.requestId().equals(r.getId())));
    }

    @Test
    void humanZeroActionApprovalUsesTheSameImmediateCompletionBoundary() {
        var r = approved(List.of());
        assertEquals(RequestStatus.EXECUTED, r.getStatus());
        assertEquals(ApprovalEvidence.ActorKind.HUMAN, plan(r.getId()).approval().actorKind());
        assertEquals(1, probe.approvals(r.getId()));
        assertEquals(1, probe.completed(r.getId()));
        assertTrue(jobs.findByRequestIdOrderByCreatedAtAsc(r.getId()).isEmpty());
        assertNull(r.getExecutionStartedAt());
        assertEquals(stored(r.getId()).getVersion(), r.getVersion());
    }

    @Test
    void configurationEditsAndApprovalReplayCannotAddRemoveOrChangeFrozenActions() {
        var r = approved(List.of(JobActions.ACCOUNTING_EXPORT));
        var p = plan(r.getId());
        var ids = jobs.findByRequestIdOrderByCreatedAtAsc(r.getId()).stream().map(ExecutionJob::getId).toList();
        var factsBefore = audit.findByRequestId(r.getId());
        var intentsBefore = intents(r.getId());
        long version = stored(r.getId()).getVersion();
        jdbc.update("UPDATE request_types SET execution_actions=?::jsonb WHERE id=?",
                "[\"PURCHASE_TICKET\",\"PROVISIONING\"]", p.typeId());
        tx().executeWithoutResult(s -> {
            requestWriteLock.findByIdForUpdate(r.getId()).orElseThrow();
            events.publish(new RequestApproved(r.getId(), p.id(), p.approval()));
        });
        assertEquals(p, plan(r.getId()));
        assertEquals(ids,
                jobs.findByRequestIdOrderByCreatedAtAsc(r.getId()).stream().map(ExecutionJob::getId).toList());
        assertEquals(factsBefore, audit.findByRequestId(r.getId()));
        assertEquals(intentsBefore, intents(r.getId()));
        assertEquals(version, stored(r.getId()).getVersion());
    }

    @Test
    void finalizationReadsCurrentConfigurationEvenWhenTheTypeWasAlreadyManaged() {
        var r = submit(draft(type(List.of(JobActions.ACCOUNTING_EXPORT), false)));
        tx().executeWithoutResult(s -> {
            var loaded = requests.findById(r.getId()).orElseThrow();
            assertEquals(List.of(JobActions.ACCOUNTING_EXPORT), loaded.getType().getExecutionActions());
            jdbc.update(
                    "UPDATE request_types SET execution_actions='[\"PROVISIONING\"]'::jsonb,updated_at=? WHERE id=?",
                    java.sql.Timestamp.from(now.minusSeconds(1)), loaded.getType().getId());
            var completedReview = approve(r);
            var p = plan(r.getId());
            assertEquals(List.of(JobActions.PROVISIONING),
                    p.actions().stream().map(ExecutionPlan.Action::action).toList());
            assertEquals(now.minusSeconds(1), p.sourceTypeUpdatedAt());
            assertEquals(p.id(), completedReview.getExecutionPlanId());
        });
    }

    @Test
    void twoLastJobFinishesSerializeToExactlyOneCompletionFactAndEvent() throws Exception {
        var r = approved(List.of(JobActions.ACCOUNTING_EXPORT, JobActions.PROVISIONING));
        var claims = jobs.findByRequestIdOrderByCreatedAtAsc(r.getId()).stream().map(this::claim).toList();
        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = new ArrayList<Future<Boolean>>();
            for (var c : claims)
                results.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return finish.finish(c, Map.of("ok", true), null, true);
                }));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            release.countDown();
            for (var result : results)
                assertTrue(result.get(10, TimeUnit.SECONDS));
        }
        assertEquals(RequestStatus.EXECUTED, stored(r.getId()).getStatus());
        assertTrue(plans.allCompleted(plan(r.getId()).id()));
        assertEquals(1, probe.completed(r.getId()));
        assertEquals(1, facts(r.getId(), "EXECUTION", "COMPLETED"));
        assertEquals(2,
                jdbc.queryForObject(
                        "SELECT count(*) FROM outbox WHERE idempotency_key LIKE ? OR idempotency_key LIKE ?",
                        Integer.class, "req:" + r.getId() + ":EXECUTED:%", "req:" + r.getId() + ":NOTIFY_EXECUTED:%"));
    }

    @Test
    void oneDeadLetterPreventsPlanCompletionEvenAfterOtherActionsSucceed() {
        var r = approved(List.of(JobActions.ACCOUNTING_EXPORT, JobActions.PROVISIONING));
        var rows = jobs.findByRequestIdOrderByCreatedAtAsc(r.getId());
        var a = claim(rows.get(0));
        finish.finish(a, Map.of("ok", true), null, true);
        var b = claim(rows.get(1));
        jdbc.update("UPDATE execution_jobs SET max_retries=1 WHERE id=?", b.job().getId());
        finish.finish(b, null, new RuntimeException("failed"), true);
        assertEquals(RequestStatus.APPROVED, stored(r.getId()).getStatus());
        assertFalse(plans.allCompleted(plan(r.getId()).id()));
        assertEquals(0, probe.completed(r.getId()));
    }

    private void fault(String predicate, Runnable body) {
        String fn = "m4_fault_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + fn
                + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'M4 audit failure' USING ERRCODE='23514'; END; $$");
        jdbc.execute("CREATE TRIGGER " + fn + " BEFORE INSERT ON lifecycle_audit_events FOR EACH ROW WHEN (" + predicate
                + ") EXECUTE FUNCTION " + fn + "()");
        try {
            body.run();
        }
        finally {
            jdbc.execute("DROP TRIGGER " + fn + " ON lifecycle_audit_events");
            jdbc.execute("DROP FUNCTION " + fn + "()");
        }
    }

    @Test
    void approvalFactFailureRollsBackVotePlanJobsAndNotificationIntents() {
        var r = submit(draft(type(List.of(JobActions.ACCOUNTING_EXPORT), false)));
        var history = audit.findByRequestId(r.getId());
        var messages = intents(r.getId());
        fault("NEW.request_id=" + r.getId() + " AND NEW.event_type='APPROVAL'",
                () -> assertThrows(org.springframework.dao.DataAccessException.class, () -> approve(r)));
        assertEquals(RequestStatus.IN_REVIEW, stored(r.getId()).getStatus());
        assertEquals(r.getVersion(), stored(r.getId()).getVersion());
        assertTrue(plans.findByRequestId(r.getId()).isEmpty());
        assertTrue(jobs.findByRequestIdOrderByCreatedAtAsc(r.getId()).isEmpty());
        assertNull(stored(r.getId()).getExecutionPlanId());
        var step = steps.findByRequestIdOrderById(r.getId()).getFirst();
        assertEquals(StepState.PENDING, step.getState());
        assertTrue(decisions.findByStepInstanceIdOrderById(step.getId()).isEmpty());
        assertEquals(history, audit.findByRequestId(r.getId()));
        assertEquals(messages, intents(r.getId()));
    }

    @Test
    void emptyCompletionFailureRollsBackTheEntireAllSkippedSubmission() {
        var r = draft(type(List.of(), true));
        var history = audit.findByRequestId(r.getId());
        fault("NEW.request_id=" + r.getId() + " AND NEW.event_type='EXECUTION' AND NEW.action='COMPLETED'",
                () -> assertThrows(org.springframework.dao.DataAccessException.class, () -> submit(r)));
        assertEquals(RequestStatus.DRAFT, stored(r.getId()).getStatus());
        assertNull(stored(r.getId()).getSubmittedAt());
        assertNull(stored(r.getId()).getExecutionPlanId());
        assertTrue(plans.findByRequestId(r.getId()).isEmpty());
        assertTrue(steps.findByRequestIdOrderById(r.getId()).isEmpty());
        assertTrue(intents(r.getId()).isEmpty());
        assertEquals(history, audit.findByRequestId(r.getId()));
        assertEquals(RequestStatus.EXECUTED, submit(r).getStatus());
    }

    @Test
    void completedEventReplayAndFinalizerReplayPreserveIdsVersionsAndIntents() {
        var r = approved(List.of());
        var p = plan(r.getId());
        var history = audit.findByRequestId(r.getId());
        var messages = intents(r.getId());
        long version = r.getVersion();
        tx().executeWithoutResult(s -> {
            requestWriteLock.findByIdForUpdate(r.getId()).orElseThrow();
            events.publish(new RequestApproved(r.getId(), p.id(), p.approval()));
            events.publish(new RequestExecuted(r.getId(), p.id(), 0));
            finalizer.execute(r.getId(), p.approval());
        });
        assertEquals(version, stored(r.getId()).getVersion());
        assertEquals(p, plan(r.getId()));
        assertEquals(history, audit.findByRequestId(r.getId()));
        assertEquals(messages, intents(r.getId()));
    }

    @Test
    void cancellationAndApprovalReplayNeverRecreateStoppedJobs() {
        var r = approved(List.of(JobActions.ACCOUNTING_EXPORT));
        var p = plan(r.getId());
        cancel.execute(r.getId(), caller("alice"));
        var messages = intents(r.getId());
        tx().executeWithoutResult(s -> {
            requestWriteLock.findByIdForUpdate(r.getId()).orElseThrow();
            events.publish(new RequestApproved(r.getId(), p.id(), p.approval()));
        });
        assertEquals(RequestStatus.CANCELLED, stored(r.getId()).getStatus());
        assertEquals(JobStatus.CANCELLED, jobs.findByRequestIdOrderByCreatedAtAsc(r.getId()).getFirst().getStatus());
        assertEquals(messages, intents(r.getId()));
    }

    @Test
    void manifestEvidenceActionsAndBindingsCannotBeRewrittenOrExtended() {
        var r = approved(List.of(JobActions.ACCOUNTING_EXPORT));
        var p = plan(r.getId());
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("UPDATE execution_plans SET approval='{}'::jsonb WHERE id=?", p.id()));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc
            .update("UPDATE execution_plan_actions SET input_payload='{}'::jsonb WHERE plan_id=?", p.id()));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("DELETE FROM execution_plan_job_bindings WHERE plan_id=?", p.id()));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
                "INSERT INTO execution_plan_actions(plan_id,position,action,idempotency_key,input_payload) VALUES (?,2,'PROVISIONING',?,'{}'::jsonb)",
                p.id(), "extra:" + r.getId()));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
                "INSERT INTO execution_jobs(request_id,idempotency_key,action,input_payload) VALUES (?,?,'PROVISIONING','{}'::jsonb)",
                r.getId(), "rogue:" + r.getId()));
        assertTrue(plans.jobsMatch(p.id()));
        assertEquals(1, jobs.countByRequestId(r.getId()));
    }

    @Test
    void missingExpectedJobCannotCountAsSuccess() {
        tx().executeWithoutResult(s -> {
            s.setRollbackOnly();
            var r = draft(type(List.of(JobActions.ACCOUNTING_EXPORT, JobActions.PROVISIONING), true));
            r = requestWriteLock.findByIdForUpdate(r.getId()).orElseThrow();
            r.markSubmitted(now);
            requests.save(r);
            UUID id = UUID.randomUUID();
            var actions = List.of(
                    new ExecutionPlan.Action(1, JobActions.ACCOUNTING_EXPORT, "req:" + r.getId() + ":ACCOUNTING_EXPORT",
                            r.getPayload(), r.getCorrelationId()),
                    new ExecutionPlan.Action(2, JobActions.PROVISIONING, "req:" + r.getId() + ":PROVISIONING",
                            r.getPayload(), r.getCorrelationId()));
            var plan = new ExecutionPlan(id, r.getId(), r.getType().getId(), r.getType().getCode(),
                    r.getType().getUpdatedAt(), now, ApprovalEvidence.allSkipped(caller("alice").id(), "alice"),
                    actions);
            r.approve(plan.approvalPlan(), List.of(), List.of());
            approvals.saveApproval(r, plan);
            r.observeReconciledExecutionStart(now);
            requests.save(r);
            var a = actions.getFirst();
            var completed = new ExecutionJob(a.idempotencyKey(), r.getId(), a.action(), a.correlationId());
            completed.freezeInput(a.input());
            completed.markStarted(now, UUID.randomUUID(), now.plusSeconds(60));
            completed.markCompleted(Map.of("legacyProof", true), now);
            jobs.save(completed);
            plans.bindJob(id, a.action(), completed.getId());
            assertEquals(1, jobs.countByRequestId(r.getId()));
            assertEquals(0, jobs.countByRequestIdAndStatusNot(r.getId(), JobStatus.COMPLETED));
            assertFalse(plans.allCompleted(id));
            completion.completeLocked(r, null);
            assertEquals(RequestStatus.APPROVED, r.getStatus());
        });
    }

    @Test
    void missingManifestIsNotTreatedAsAnEmptyPlanAndReadAccessIsUnchanged() throws Exception {
        var draft = draft(type(List.of(), false));
        assertFalse(mediator.send(new GetExecutionPlan(draft.getId(), caller("alice"))).known());
        tx().executeWithoutResult(s -> {
            var r = requestWriteLock.findByIdForUpdate(draft.getId()).orElseThrow();
            r.markSubmitted(now);
            requests.save(r);
            LegacyRequestFixtures.approveWithoutManifest(jdbc, r.getId(), now, true);
            completion.completeLocked(requestWriteLock.findByIdForUpdate(r.getId()).orElseThrow(), null);
        });
        assertEquals(RequestStatus.APPROVED, stored(draft.getId()).getStatus());
        assertFalse(mediator.send(new GetExecutionPlan(draft.getId(), caller("alice"))).known());
        assertThrows(ConflictException.class, () -> cancel.execute(draft.getId(), caller("alice")));
        var r = approved(List.of(JobActions.ACCOUNTING_EXPORT));
        var owner = caller("alice");
        var auth = new org.springframework.security.authentication.TestingAuthenticationToken(
                new UserPrincipal(owner.id(), owner.username(), Set.of(), null), null, "ROLE_TEST");
        mvc.perform(get("/api/v1/requests/" + r.getId() + "/execution-plan").with(authentication(auth)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.known").value(true))
            .andExpect(jsonPath("$.plan.id").value(r.getExecutionPlanId().toString()))
            .andExpect(jsonPath("$.plan.actions[0].input.amount").value(100));
        mvc.perform(get("/api/v1/requests/" + r.getId()).with(authentication(auth)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.executionPlanId").value(r.getExecutionPlanId().toString()));
    }

    @Test
    void unsatisfiedReviewAndForgedApprovalEventsCannotFinalize() {
        var submitted = submit(draft(type(List.of(JobActions.ACCOUNTING_EXPORT), false)));
        assertThrows(ConflictException.class, () -> tx().execute(
                s -> finalizer.execute(submitted.getId(), ApprovalEvidence.allSkipped(caller("alice").id(), "alice"))));
        var r = approve(submitted);
        var approved = r;
        var p = plan(r.getId());
        assertThrows(ConflictException.class, () -> tx().executeWithoutResult(
                s -> events.publish(new RequestApproved(approved.getId(), UUID.randomUUID(), p.approval()))));
        assertEquals(p, plan(r.getId()));
    }

    static class Probe {

        final Map<Long, AtomicInteger> approved = new ConcurrentHashMap<>(), executed = new ConcurrentHashMap<>();

        int approvals(Long id) {
            return approved.getOrDefault(id, new AtomicInteger()).get();
        }

        int completed(Long id) {
            return executed.getOrDefault(id, new AtomicInteger()).get();
        }

    }

    @TestConfiguration
    static class Observers {

        @Bean
        Probe probe() {
            return new Probe();
        }

        @Bean
        @Order(400)
        DomainEventHandler<RequestApproved> approvalObserver(Probe probe) {
            return new DomainEventHandler<>() {
                public Class<RequestApproved> eventType() {
                    return RequestApproved.class;
                }

                public void handle(RequestApproved e) {
                    probe.approved.computeIfAbsent(e.requestId(), id -> new AtomicInteger()).incrementAndGet();
                }
            };
        }

        @Bean
        @Order(400)
        DomainEventHandler<RequestExecuted> completionObserver(Probe probe) {
            return new DomainEventHandler<>() {
                public Class<RequestExecuted> eventType() {
                    return RequestExecuted.class;
                }

                public void handle(RequestExecuted e) {
                    probe.executed.computeIfAbsent(e.requestId(), id -> new AtomicInteger()).incrementAndGet();
                }
            };
        }

    }

}
