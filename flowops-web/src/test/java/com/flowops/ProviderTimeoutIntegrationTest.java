package com.flowops;

import com.flowops.application.execution.*;
import com.flowops.application.execution.port.*;
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
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProviderTimeoutIntegrationTest extends AbstractIntegrationTest {

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
    ExecutionJobRepository jobs;

    @Autowired
    StartExecutionJob start;

    @Autowired
    FinishExecutionJob finish;

    @Autowired
    ProcessExecutionJobs worker;

    @Autowired
    RecoverExecution recovery;

    @Autowired
    ProviderContractRepository snapshots;

    @Autowired
    ExecutionAttemptRepository ledger;

    @Autowired
    CancelRequest cancel;

    @Autowired
    JdbcTemplate jdbc;

    private final Instant now = Instant.parse("2026-12-05T12:00:00Z");

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

    private ExecutionRecoveryTransactions.Input input(Fixture f) {
        return new ExecutionRecoveryTransactions.Input(UUID.randomUUID(), request(f).getVersion(),
                "Investigated timeout");
    }

    private ProviderContract shortContract() {
        return new ProviderContract("TEST_PROVIDER", "v1", ProviderContract.Transport.LOCAL, Duration.ZERO,
                Duration.ZERO, Duration.ofMillis(500), Duration.ofMillis(300), true, true, false);
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
            .send(new CreateDraftCommand("EXPENSE", Map.of("amount", 100, "description", "Provider timeout"),
                    caller("alice")))
            .request();
        mediator.send(new SubmitRequestCommand(r.getId(), caller("alice")));
        mediator.send(new DecideStepCommand(r.getId(), DecisionAction.APPROVE, "Reviewed", null, caller("admin")));
        return new Fixture(r.getId(), jobs.findByRequestIdOrderByCreatedAtAsc(r.getId()).getFirst().getId());
    }

    @Test
    void timeoutStopsWaitingHoldsTheAttemptAndOnlyReconciliationCanAcceptLateSuccess() throws Exception {
        var f = approved();
        doReturn(shortContract()).when(handler).contract(anyString());
        var release = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        when(handler.execute(argThat(c -> c != null && c.requestId().equals(f.requestId())))).thenAnswer(inv -> {
            try {
                boolean done = false;
                while (!done)
                    try {
                        release.await();
                        done = true;
                    }
                    catch (InterruptedException ignored) {
                    }
                return Map.of("receipt", "late-success");
            }
            finally {
                returned.countDown();
            }
        });
        try {
            worker.execute();
            assertEquals(JobStatus.RECONCILIATION_REQUIRED, job(f).getStatus());
            assertEquals(0, job(f).getRetryCount());
            assertEquals(1, job(f).getAttemptCount());
            assertNotNull(request(f).getExecutionStartedAt());
            assertNull(job(f).getResult());
            var event = ledger.events(f.requestId(), f.jobId(), null, 10).getFirst();
            assertEquals("TIMEOUT", event.details().get("providerFailureReason"));
            assertEquals(shortContract(), snapshots.attempt(job(f).getClaimToken()).orElseThrow());
            assertThrows(ConflictException.class, () -> cancel.execute(f.requestId(), caller("alice")));
            worker.execute();
            verify(handler, times(1)).execute(any());
        }
        finally {
            release.countDown();
            assertTrue(returned.await(5, TimeUnit.SECONDS));
        }
        assertEquals(JobStatus.RECONCILIATION_REQUIRED, job(f).getStatus());
        assertNull(job(f).getResult());
        when(handler.reconcile(any())).thenReturn(new ProviderObservation(ProviderObservation.Outcome.SUCCEEDED,
                "provider:receipt", Map.of("receipt", "confirmed")));
        var result = recovery.reconcile(f.requestId(), f.jobId(), input(f), caller("admin"));
        assertEquals(JobStatus.COMPLETED, job(f).getStatus());
        assertEquals(RequestStatus.EXECUTED, request(f).getStatus());
        assertEquals(shortContract(), snapshots.recovery(result.entry().command().id()).orElseThrow());
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("UPDATE execution_attempt_provider_contracts SET revision='v2' WHERE claim_token=?",
                        job(f).getClaimToken()));
    }

    @Test
    void lookupTimeoutStoresAnErrorAndRepeatCommandDoesNotQueryAgain() throws Exception {
        var f = approved();
        var claim = start.start(f.candidate()).orElseThrow();
        finish.finish(claim, null, new RuntimeException("unknown"), false);
        doReturn(shortContract()).when(handler).contract(anyString());
        var release = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        when(handler.reconcile(any())).thenAnswer(inv -> {
            try {
                boolean done = false;
                while (!done)
                    try {
                        release.await();
                        done = true;
                    }
                    catch (InterruptedException ignored) {
                    }
                return ProviderObservation.unknown();
            }
            finally {
                returned.countDown();
            }
        });
        var command = input(f);
        try {
            var result = recovery.reconcile(f.requestId(), f.jobId(), command, caller("admin"));
            assertEquals("PROVIDER_ERROR", result.entry().observation().disposition().name());
            assertEquals("ProviderCallFailure:TIMEOUT", result.entry().observation().failureType());
            recovery.reconcile(f.requestId(), f.jobId(), command, caller("admin"));
            verify(handler, times(1)).reconcile(any());
            assertEquals(JobStatus.RECONCILIATION_REQUIRED, job(f).getStatus());
        }
        finally {
            release.countDown();
            assertTrue(returned.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void missingAndInvalidContractsFailBeforeDurableStartOrProviderInvocation() {
        var f = approved();
        doReturn(null).when(handler).contract(anyString());
        assertEquals("provider-contract-unavailable",
                assertThrows(ConflictException.class, () -> start.start(f.candidate())).getCode());
        assertNull(request(f).getExecutionStartedAt());
        assertEquals(JobStatus.PLANNED, job(f).getStatus());
        var invalid = new ProviderContract("TEST_PROVIDER", "v1", ProviderContract.Transport.LOCAL, Duration.ZERO,
                Duration.ZERO, Duration.ofSeconds(1800), Duration.ofSeconds(10), true, true, false);
        doReturn(invalid).when(handler).contract(anyString());
        assertEquals("provider-contract-invalid",
                assertThrows(ConflictException.class, () -> start.start(f.candidate())).getCode());
        assertNull(request(f).getExecutionStartedAt());
        verify(handler, never()).execute(any());
    }

    @Test
    void providerIdentityCannotChangeAcrossRetriesOrReconciliation() {
        var f = approved();
        var first = start.start(f.candidate()).orElseThrow();
        finish.finish(first, null, new RuntimeException("retryable"), true);
        when(clock.instant()).thenReturn(now.plusSeconds(6));
        doReturn(new ProviderContract("DIFFERENT_PROVIDER", "v1", ProviderContract.Transport.LOCAL, Duration.ZERO,
                Duration.ZERO, Duration.ofSeconds(20), Duration.ofSeconds(10), true, true, false))
            .when(handler)
            .contract(anyString());
        assertEquals("provider-binding-changed",
                assertThrows(ConflictException.class, () -> start.start(f.candidate())).getCode());
        assertEquals("provider-binding-changed", assertThrows(ConflictException.class,
                () -> recovery.reconcile(f.requestId(), f.jobId(), input(f), caller("admin")))
            .getCode());
        assertEquals(1, job(f).getAttemptCount());
        verify(handler, never()).execute(any());
        verify(handler, never()).reconcile(any());
    }

    @Test
    void contractStorageFailureRollsBackAuthorization() {
        var f = approved();
        String fn = "contract_fault_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + fn
                + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'contract evidence failure' USING ERRCODE='23514'; END; $$");
        jdbc.execute("CREATE TRIGGER " + fn
                + " BEFORE INSERT ON execution_attempt_provider_contracts FOR EACH ROW EXECUTE FUNCTION " + fn + "()");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> start.start(f.candidate()));
            assertNull(request(f).getExecutionStartedAt());
            assertEquals(JobStatus.PLANNED, job(f).getStatus());
        }
        finally {
            jdbc.execute("DROP TRIGGER " + fn + " ON execution_attempt_provider_contracts");
            jdbc.execute("DROP FUNCTION " + fn + "()");
        }
    }

}
