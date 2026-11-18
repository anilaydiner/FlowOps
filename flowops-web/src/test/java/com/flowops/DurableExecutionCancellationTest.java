package com.flowops;

import com.flowops.application.execution.*;
import com.flowops.application.mediator.Mediator;
import com.flowops.application.request.command.*;
import com.flowops.application.request.lifecycle.CancelRequest;
import com.flowops.domain.exception.ConflictException;
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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@AutoConfigureMockMvc
class DurableExecutionCancellationTest extends AbstractIntegrationTest {
    @MockitoBean ActionHandler handler;
    @MockitoBean Clock clock;
    @Autowired Mediator mediator;
    @Autowired UserRepository users;
    @Autowired RequestRepository requests;
    @Autowired ExecutionJobRepository jobs;
    @Autowired CancelRequest cancel;
    @Autowired StartExecutionJob start;
    @Autowired FinishExecutionJob finish;
    @Autowired ProcessExecutionJobs worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    private final Instant now=Instant.parse("2026-12-02T12:00:00Z");
    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }
    private CallerIdentity caller(String name) { var u=users.findByUsername(name).orElseThrow();return new CallerIdentity(u.getId(),u.getUsername()); }
    private record Fixture(Long requestId,Long jobId) {
        ExecutionJobRepository.Candidate candidate() { return new ExecutionJobRepository.Candidate(jobId,requestId); }
    }
    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(now);when(handler.supportsIdempotentRetries()).thenReturn(true);TestProviderContracts.stub(handler);
        when(handler.execute(any())).thenAnswer(call->{ActionCommand command=call.getArgument(0);return command==null?Map.of():Map.of("ok",true,"key",command.idempotencyKey());});
        // Contexts share a database; finish old ready work before asserting calls for this fixture.
        for(int i=0;i<30 && !jobs.findCandidates(now,50).isEmpty();i++) worker.execute();
        clearInvocations(handler);
    }
    private Fixture approved() {
        var request=mediator.send(new CreateDraftCommand("EXPENSE",Map.of("amount",100,"description","Durable start"),caller("alice"))).request();
        mediator.send(new SubmitRequestCommand(request.getId(),caller("alice")));
        var approved=mediator.send(new DecideStepCommand(request.getId(),DecisionAction.APPROVE,"Reviewed",null,caller("admin"))).request();
        assertNotNull(approved.getExecutionPlannedAt());
        assertEquals(requests.findById(request.getId()).orElseThrow().getVersion(),approved.getVersion());
        return new Fixture(request.getId(),jobs.findByRequestIdOrderByCreatedAtAsc(request.getId()).getFirst().getId());
    }
    private Request request(Fixture f) { return requests.findById(f.requestId()).orElseThrow(); }
    private ExecutionJob job(Fixture f) { return jobs.findById(f.jobId()).orElseThrow(); }
    private int events(Fixture f,String action) { return jdbc.queryForObject("SELECT count(*) FROM lifecycle_audit_events WHERE request_id=? AND action=?",Integer.class,f.requestId(),action); }

    @Test void cancellationWinsBeforeStartAndReplayIsAuthorizedAndIdempotent() {
        var f=approved();var owner=caller("alice");
        cancel.execute(f.requestId(),owner);
        assertEquals(RequestStatus.CANCELLED,request(f).getStatus());assertEquals(JobStatus.CANCELLED,job(f).getStatus());
        assertEquals(now,job(f).getCancelledAt());assertEquals(owner.id(),job(f).getCancelledBy());
        assertNull(request(f).getExecutionStartedAt());assertEquals(0,job(f).getAttemptCount());
        long version=request(f).getVersion();int facts=events(f,"CANCELLED");
        cancel.execute(f.requestId(),owner);
        assertEquals(version,request(f).getVersion());assertEquals(facts,events(f,"CANCELLED"));
        assertTrue(start.start(f.candidate()).isEmpty());worker.execute();
        verify(handler,never()).execute(argThat(command->command.requestId().equals(f.requestId())));
    }

    @Test void cancellationHoldingTheParentPreventsAStartAndMakesZeroHandlerCalls() throws Exception {
        var f=approved();
        try(var pool=Executors.newSingleThreadExecutor()) {
            tx().executeWithoutResult(status->{
                cancel.execute(f.requestId(),caller("alice"));
                try { pool.submit(()->worker.execute()).get(3,TimeUnit.SECONDS); }
                catch(Exception e) { throw new AssertionError(e); }
            });
        }
        assertEquals(JobStatus.CANCELLED,job(f).getStatus());
        verify(handler,never()).execute(argThat(command->command.requestId().equals(f.requestId())));
    }

    @Test void committedStartIsVisibleWhileTheHandlerRunsWithoutATransactionOrParentLock() throws Exception {
        var f=approved();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(handler.execute(argThat(command->command!=null && command.requestId().equals(f.requestId())))).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertNotNull(request(f).getExecutionStartedAt());assertEquals(JobStatus.STARTED,job(f).getStatus());
            entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return Map.of("ok",true);
        });
        try(var pool=Executors.newFixedThreadPool(2)) {
            var processing=pool.submit(()->worker.execute());
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                var cancellation=pool.submit(()->assertThrows(ConflictException.class,()->cancel.execute(f.requestId(),caller("alice"))));
                assertEquals("execution-already-started",cancellation.get(3,TimeUnit.SECONDS).getCode());
                assertEquals(RequestStatus.APPROVED,request(f).getStatus());
            } finally { release.countDown(); }
            processing.get(10,TimeUnit.SECONDS);
        }
        assertEquals(JobStatus.COMPLETED,job(f).getStatus());assertEquals(RequestStatus.EXECUTED,request(f).getStatus());
    }

    @Test void failedAttemptsRetainTheLatchAndStableInputAndKeyAcrossRetries() {
        var f=approved();var first=start.start(f.candidate()).orElseThrow();
        assertTrue(finish.finish(first,null,new ActionHandler.ActionExecutionException("retryable"),true));
        assertEquals(JobStatus.FAILED,job(f).getStatus());var started=request(f).getExecutionStartedAt();
        assertEquals("execution-already-started",assertThrows(ConflictException.class,()->cancel.execute(f.requestId(),caller("alice"))).getCode());
        when(clock.instant()).thenReturn(now.plusSeconds(5));
        var second=start.start(f.candidate()).orElseThrow();
        assertNotEquals(first.token(),second.token());assertEquals(first.command(),second.command());
        assertEquals(started,request(f).getExecutionStartedAt());assertEquals(2,job(f).getAttemptCount());
        assertFalse(finish.finish(first,Map.of("stale",true),null,true));
        assertTrue(finish.finish(second,Map.of("ok",true),null,true));
        assertEquals(JobStatus.COMPLETED,job(f).getStatus());assertEquals(2,events(f,"STARTED"));
    }

    @Test void failedStartCommitRollsBackBothLatchAndClaimBeforeAnyExternalCall() {
        var f=approved();String fault="start_fault_"+UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION "+fault+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'start audit failure' USING ERRCODE='23514'; END; $$");
        try {
            jdbc.execute("CREATE TRIGGER "+fault+" BEFORE INSERT ON lifecycle_audit_events FOR EACH ROW WHEN (NEW.request_id="+f.requestId()+" AND NEW.action='STARTED') EXECUTE FUNCTION "+fault+"()");
            assertThrows(org.springframework.dao.DataAccessException.class,()->start.start(f.candidate()));
            assertNull(request(f).getExecutionStartedAt());assertEquals(JobStatus.PLANNED,job(f).getStatus());assertEquals(0,job(f).getAttemptCount());
            verify(handler,never()).execute(argThat(command->command.requestId().equals(f.requestId())));
        } finally { jdbc.execute("DROP TRIGGER IF EXISTS "+fault+" ON lifecycle_audit_events");jdbc.execute("DROP FUNCTION "+fault+"()"); }
        cancel.execute(f.requestId(),caller("alice"));assertEquals(JobStatus.CANCELLED,job(f).getStatus());
    }

    @Test void abandonedOrLateClaimsAreHeldForReconciliationAndNeverBlindlyReplayed() {
        var f=approved();var claim=start.start(f.candidate()).orElseThrow();
        when(clock.instant()).thenReturn(now.plusSeconds(300));
        assertTrue(start.start(f.candidate()).isEmpty());assertEquals(JobStatus.RECONCILIATION_REQUIRED,job(f).getStatus());
        assertFalse(finish.finish(claim,Map.of("late",true),null,true));
        assertThrows(ConflictException.class,()->cancel.execute(f.requestId(),caller("alice")));
        worker.execute();verify(handler,never()).execute(argThat(command->command.requestId().equals(f.requestId())));
    }

    @Test void leaseExpiryAlsoFencesAResultWhenNoScannerHasSeenTheExpiredClaim() {
        var f=approved();var claim=start.start(f.candidate()).orElseThrow();
        when(clock.instant()).thenReturn(now.plusSeconds(300));
        assertFalse(finish.finish(claim,Map.of("late",true),null,true));assertEquals(JobStatus.RECONCILIATION_REQUIRED,job(f).getStatus());
        assertNull(job(f).getResult());assertNotNull(request(f).getExecutionStartedAt());
    }

    @Test void anUnsafeHandlerFailureRequiresReconciliationInsteadOfRetry() {
        var f=approved();when(handler.supportsIdempotentRetries()).thenReturn(false);
        when(handler.execute(argThat(command->command!=null && command.requestId().equals(f.requestId())))).thenThrow(new RuntimeException("ambiguous provider failure"));
        worker.execute();assertEquals(JobStatus.RECONCILIATION_REQUIRED,job(f).getStatus());
        worker.execute();verify(handler,times(1)).execute(argThat(command->command.requestId().equals(f.requestId())));
        assertEquals("execution-already-started",assertThrows(ConflictException.class,()->cancel.execute(f.requestId(),caller("alice"))).getCode());
    }

    @Test void failedFinishPreservesTheDurableStartAndDoesNotRelabelSuccessAsARetryableFailure() {
        var f=approved();String fault="finish_fault_"+UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION "+fault+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'finish audit failure' USING ERRCODE='23514'; END; $$");
        try {
            jdbc.execute("CREATE TRIGGER "+fault+" BEFORE INSERT ON lifecycle_audit_events FOR EACH ROW WHEN (NEW.request_id="+f.requestId()+" AND NEW.action='COMPLETED') EXECUTE FUNCTION "+fault+"()");
            worker.execute();assertEquals(JobStatus.STARTED,job(f).getStatus());assertNotNull(request(f).getExecutionStartedAt());
            worker.execute();verify(handler,times(1)).execute(argThat(command->command.requestId().equals(f.requestId())));
            assertEquals(0,events(f,"FAILED"));assertThrows(ConflictException.class,()->cancel.execute(f.requestId(),caller("alice")));
        } finally { jdbc.execute("DROP TRIGGER IF EXISTS "+fault+" ON lifecycle_audit_events");jdbc.execute("DROP FUNCTION "+fault+"()"); }
    }

    @Test void cancellationApiReturnsStableConflictCodeAndStorageProtectsTheLatch() throws Exception {
        var f=approved();start.start(f.candidate()).orElseThrow();
        var owner=caller("alice");var authentication=new org.springframework.security.authentication.TestingAuthenticationToken(new UserPrincipal(owner.id(),owner.username(),Set.of(),null),null,"ROLE_TEST");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/requests/"+f.requestId()+"/cancel")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(authentication)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("execution-already-started"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE requests SET execution_started_at=NULL WHERE id=?",f.requestId()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE execution_jobs SET started_at=NULL WHERE id=?",f.jobId()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE execution_jobs SET input_payload='{}'::jsonb WHERE id=?",f.jobId()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE execution_jobs SET claim_token=? WHERE id=?",UUID.randomUUID(),f.jobId()));
    }

    @Test void cancellationFailureRollsBackRequestJobAndCancellationIntentsTogether() {
        var f=approved();long version=request(f).getVersion();
        String fault="cancel_fault_"+UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION "+fault+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'cancel audit failure' USING ERRCODE='23514'; END; $$");
        try {
            jdbc.execute("CREATE TRIGGER "+fault+" BEFORE INSERT ON lifecycle_audit_events FOR EACH ROW WHEN (NEW.request_id="+f.requestId()+" AND NEW.event_type='JOB' AND NEW.action='CANCELLED') EXECUTE FUNCTION "+fault+"()");
            assertThrows(org.springframework.dao.DataAccessException.class,()->cancel.execute(f.requestId(),caller("alice")));
            assertEquals(RequestStatus.APPROVED,request(f).getStatus());assertEquals(version,request(f).getVersion());
            assertEquals(JobStatus.PLANNED,job(f).getStatus());assertNull(job(f).getCancelledAt());assertNull(request(f).getExecutionStartedAt());
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM outbox WHERE idempotency_key LIKE ?",Integer.class,"req:"+f.requestId()+":%CANCELLED:%"));
        } finally { jdbc.execute("DROP TRIGGER IF EXISTS "+fault+" ON lifecycle_audit_events");jdbc.execute("DROP FUNCTION "+fault+"()"); }
        assertTrue(start.start(f.candidate()).isPresent());
    }

    @Test void oneJobsFinishFailureCannotRollBackAnotherJobsCommittedSuccess() {
        var a=approved();var b=approved();
        String fault="isolation_fault_"+UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION "+fault+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated finish failure' USING ERRCODE='23514'; END; $$");
        try {
            jdbc.execute("CREATE TRIGGER "+fault+" BEFORE INSERT ON lifecycle_audit_events FOR EACH ROW WHEN (NEW.request_id="+a.requestId()+" AND NEW.action='COMPLETED') EXECUTE FUNCTION "+fault+"()");
            worker.execute();
            assertEquals(JobStatus.STARTED,job(a).getStatus());assertNotNull(request(a).getExecutionStartedAt());
            assertEquals(JobStatus.COMPLETED,job(b).getStatus());assertEquals(RequestStatus.EXECUTED,request(b).getStatus());
        } finally { jdbc.execute("DROP TRIGGER IF EXISTS "+fault+" ON lifecycle_audit_events");jdbc.execute("DROP FUNCTION "+fault+"()"); }
    }

    @Test void aCapturedEmptyPlanCompletesButAnUnknownApprovedPlanStillNeedsDisposition() {
        var owner=caller("alice");
        var request=mediator.send(new CreateDraftCommand("POLICY_EXCEPTION",Map.of("policy","Travel","justification","Needed"),owner)).request();
        mediator.send(new SubmitRequestCommand(request.getId(),owner));
        var approved=mediator.send(new DecideStepCommand(request.getId(),DecisionAction.APPROVE,"Reviewed",null,caller("admin"))).request();
        assertNotNull(approved.getExecutionPlannedAt());assertTrue(jobs.findByRequestIdOrderByCreatedAtAsc(request.getId()).isEmpty());
        assertEquals(RequestStatus.EXECUTED,approved.getStatus());
        assertThrows(ConflictException.class,()->cancel.execute(request.getId(),owner));
        var unknown=mediator.send(new CreateDraftCommand("EXPENSE",Map.of("amount",100,"description","Unknown plan"),owner)).request();
        tx().executeWithoutResult(status->{unknown.markSubmitted(now);requests.save(unknown);LegacyRequestFixtures.approveWithoutManifest(jdbc,unknown.getId(),now,false);});
        assertEquals("execution-plan-unknown",assertThrows(ConflictException.class,()->cancel.execute(unknown.getId(),owner)).getCode());
    }
    @Test void aRetryIsHeldIfTheCurrentAdapterNoLongerGuaranteesIdempotency() {
        var f=approved();var claim=start.start(f.candidate()).orElseThrow();
        finish.finish(claim,null,new ActionHandler.ActionExecutionException("Retryable failure"),true);
        when(clock.instant()).thenReturn(now.plusSeconds(5));when(handler.supportsIdempotentRetries()).thenReturn(false);
        assertTrue(start.start(f.candidate()).isEmpty());assertEquals(JobStatus.RECONCILIATION_REQUIRED,job(f).getStatus());
        assertEquals(1,job(f).getAttemptCount());assertNotNull(request(f).getExecutionStartedAt());
        verify(handler,never()).execute(argThat(command->command.requestId().equals(f.requestId())));
    }

}
