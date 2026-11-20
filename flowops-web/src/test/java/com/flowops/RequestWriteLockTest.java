package com.flowops;

import com.flowops.application.request.port.RequestWriteLock;
import com.flowops.application.mediator.Mediator;
import com.flowops.application.request.ClarificationService;
import com.flowops.application.request.command.*;
import com.flowops.application.request.sla.ScanSla;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.port.UserRepository;
import com.flowops.domain.request.*;
import com.flowops.domain.request.port.*;
import com.flowops.domain.execution.port.ExecutionJobRepository;
import com.flowops.domain.workflow.port.WorkflowStepRepository;
import com.flowops.worker.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class RequestWriteLockTest extends AbstractIntegrationTest {

    @MockitoBean
    JobWorker backgroundJobs;

    @MockitoBean
    OutboxDispatcher backgroundOutbox;

    @MockitoBean
    SlaScanner backgroundSla;

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
    com.flowops.application.execution.StartExecutionJob executionStart;

    @Autowired
    UserRepository users;

    @Autowired
    Mediator mediator;

    @Autowired
    ClarificationService clarificationService;

    @Autowired
    ClarificationRepository clarifications;

    @Autowired
    WorkflowStepRepository definitions;

    @Autowired
    ScanSla sla;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    private CallerIdentity caller(String name) {
        var u = users.findByUsername(name).orElseThrow();
        return new CallerIdentity(u.getId(), u.getUsername());
    }

    private Request draft(int amount) {
        return mediator
            .send(new CreateDraftCommand("EXPENSE", Map.of("amount", amount, "description", "Lock test"),
                    caller("alice")))
            .request();
    }

    private void awaitLockWait(AtomicInteger backend) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline) {
            if (backend.get() != 0 && Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock')",
                    Boolean.class, backend.get())))
                return;
            Thread.yield();
        }
        fail("Contender did not enter a PostgreSQL lock wait");
    }

    /**
     * Latches coordinate transactions; pg_stat_activity proves an actual database wait.
     */
    private <T> T whileLocked(Long id, Consumer<Request> holderChange, Callable<T> contender) throws Exception {
        var tx = new TransactionTemplate(transactions);
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger backend = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                Request request = requestWriteLock.findByIdForUpdate(id).orElseThrow();
                holderChange.accept(request);
                held.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertTrue(held.await(10, TimeUnit.SECONDS));
            var waiting = pool.submit(() -> tx.execute(status -> {
                backend.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                try {
                    return contender.call();
                }
                catch (RuntimeException e) {
                    throw e;
                }
                catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
            awaitLockWait(backend);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            try {
                return waiting.get(10, TimeUnit.SECONDS);
            }
            catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException cause)
                    throw cause;
                throw e;
            }
        }
        finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void lockRequiresAnExistingTransaction() {
        Request request = draft(100);
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                () -> requestWriteLock.findByIdForUpdate(request.getId()));
    }

    @Test
    void cachedStateIsRefreshedAfterWaitingForAnotherCommit() throws Exception {
        Request request = draft(100);
        Request refreshed = whileLocked(request.getId(), r -> {
            r.cancel();
            requests.save(r);
        }, () -> {
            assertEquals(RequestStatus.DRAFT, requests.findById(request.getId()).orElseThrow().getStatus());
            return requestWriteLock.findByIdForUpdate(request.getId()).orElseThrow();
        });
        assertEquals(RequestStatus.CANCELLED, refreshed.getStatus());
        assertTrue(refreshed.getVersion() > request.getVersion());
    }

    @Test
    void lockWaitTimesOutAndRollsBackWithoutLeakingTheTransactionSetting() {
        Request request = draft(100);
        var tx = new TransactionTemplate(transactions);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(status -> {
                requestWriteLock.findByIdForUpdate(request.getId()).orElseThrow();
                var waiting = pool.submit(() -> tx.execute(s -> requestWriteLock.findByIdForUpdate(request.getId())));
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> waiting.get(8, TimeUnit.SECONDS));
                assertInstanceOf(org.springframework.dao.PessimisticLockingFailureException.class, failure.getCause());
            });
            assertEquals("0", jdbc.queryForObject("SHOW lock_timeout", String.class));
            assertEquals(RequestStatus.DRAFT, requests.findById(request.getId()).orElseThrow().getStatus());
        }
        finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cancelledRequestCannotAcquireALateDecision() throws Exception {
        Request request = draft(100);
        var owner = caller("alice");
        var admin = caller("admin");
        mediator.send(new SubmitRequestCommand(request.getId(), owner));
        assertThrows(ConflictException.class, () -> whileLocked(request.getId(),
                r -> mediator.send(new CancelRequestCommand(r.getId(), owner)), () -> mediator
                    .send(new DecideStepCommand(request.getId(), DecisionAction.APPROVE, "late", null, admin))));
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertEquals(RequestStatus.CANCELLED, requests.findById(request.getId()).orElseThrow().getStatus());
            assertTrue(decisions.findByRequestIdOrderById(request.getId()).isEmpty());
        });
    }

    @Test
    void legacyDecisionBodyCannotAdvanceTheNextStepAfterALockWait() throws Exception {
        Request request = draft(6000);
        var admin = caller("admin");
        mediator.send(new SubmitRequestCommand(request.getId(), caller("alice")));
        assertThrows(ConflictException.class, () -> whileLocked(request.getId(),
                r -> mediator.send(new DecideStepCommand(r.getId(), DecisionAction.APPROVE, "first", null, admin)),
                () -> mediator
                    .send(new DecideStepCommand(request.getId(), DecisionAction.APPROVE, "stale", null, admin))));
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var pending = steps.findTopByRequestIdAndStateOrderById(request.getId(), StepState.PENDING).orElseThrow();
            assertEquals("FINANCE_REVIEW", pending.getStepCode());
            assertEquals(0, decisions.countDistinctApprovers(pending.getId()));
        });
    }

    @Test
    void waitingAnswerCannotOverwriteTheCommittedAnswer() throws Exception {
        Request request = draft(100);
        var owner = caller("alice");
        var question = clarificationService.ask(request.getId(), "Which cost centre?", owner);
        assertThrows(ConflictException.class, () -> whileLocked(request.getId(),
                r -> clarificationService.answer(question.getId(), r.getId(), "First answer", owner), () -> {
                    // Simulate an entity cached before the competing answer commits.
                    assertFalse(clarifications.findByIdAndRequestId(question.getId(), request.getId())
                        .orElseThrow()
                        .isAnswered());
                    return clarificationService.answer(question.getId(), request.getId(), "Late answer", owner);
                }));
        assertEquals("First answer", clarifications.findById(question.getId()).orElseThrow().getAnswer());
        assertEquals(1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM lifecycle_audit_events WHERE request_id=? AND action='ANSWERED'",
                        Integer.class, request.getId()));
    }

    @Test
    void slaScanCannotExpireARequestWhoseApprovalIsInProgress() throws Exception {
        Request request = draft(100);
        var definition = definitions.findByWorkflowIdOrderById(request.getType().getDefaultWorkflowId()).getFirst();
        Integer originalSla = definition.getSlaMinutes();
        definition.changeReviewSla(1);
        definitions.save(definition);
        try {
            mediator.send(new SubmitRequestCommand(request.getId(), caller("alice")));
        }
        finally {
            definition.changeReviewSla(originalSla);
            definitions.save(definition);
        }
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            mediator.send(new DecideStepCommand(request.getId(), DecisionAction.APPROVE, "Approved before scan", null,
                    caller("admin")));
            sla.scan(); // The worker skips the busy parent rather than waiting with a
                        // stale step.
        });
        assertEquals(RequestStatus.APPROVED, requests.findById(request.getId()).orElseThrow().getStatus());
        assertEquals(0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM lifecycle_audit_events WHERE request_id=? AND event_type='SLA'",
                        Integer.class, request.getId()));
    }

    @Test
    void workerSkipsLockedParentsInsteadOfTakingTheirJobLocks() throws Exception {
        Request request = draft(100);
        mediator.send(new SubmitRequestCommand(request.getId(), caller("alice")));
        mediator
            .send(new DecideStepCommand(request.getId(), DecisionAction.APPROVE, "approved", null, caller("admin")));
        var tx = new TransactionTemplate(transactions);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(status -> {
                requestWriteLock.findByIdForUpdate(request.getId()).orElseThrow();
                try {
                    var job = jobs.findByRequestIdOrderByCreatedAtAsc(request.getId()).getFirst();
                    var result = pool
                        .submit(() -> executionStart
                            .start(new ExecutionJobRepository.Candidate(job.getId(), request.getId())))
                        .get(3, TimeUnit.SECONDS);
                    assertTrue(result.isEmpty());
                }
                catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
        }
        finally {
            pool.shutdownNow();
        }
    }

}
