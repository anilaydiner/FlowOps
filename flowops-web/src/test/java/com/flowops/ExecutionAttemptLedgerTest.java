package com.flowops;

import com.flowops.application.request.port.RequestWriteLock;
import com.flowops.application.execution.*;
import com.flowops.application.execution.model.ExecutionAttempt;
import com.flowops.application.execution.port.*;
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
import com.flowops.worker.ExecutionHeartbeatScheduler;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import static com.flowops.application.execution.HeartbeatExecutionJob.Renewal.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

@AutoConfigureMockMvc
class ExecutionAttemptLedgerTest extends AbstractIntegrationTest {
    @MockitoBean ActionHandler handler;
    @MockitoBean Clock clock;
    @MockitoBean ExecutionHeartbeats scheduling;
    @MockitoSpyBean ExecutionAttemptRecorder recorder;
    @Autowired HeartbeatExecutionJob heartbeat;
    @Autowired ExecutionAttemptRepository ledger;
    @Autowired ExecutionAttemptHistory history;
    @Autowired StartExecutionJob start;
    @Autowired FinishExecutionJob finish;
    @Autowired ProcessExecutionJobs worker;
    @Autowired RecoverExecution recovery;
    @Autowired ExecutionRecoveryTransactions recoveryTx;
    @Autowired Mediator mediator;
    @Autowired UserRepository users;
    @Autowired RequestRepository requests;
    @Autowired RequestWriteLock requestWriteLock;
    @Autowired ExecutionJobRepository jobs;
    @Autowired CancelRequest cancel;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    private ExecutionHeartbeatScheduler scheduler;
    private final Instant now=Instant.parse("2026-12-04T12:00:00Z");
    private final java.util.concurrent.atomic.AtomicReference<Instant> time=new java.util.concurrent.atomic.AtomicReference<>(now);
    private record Fixture(Long requestId,Long jobId) {
        ExecutionJobRepository.Candidate candidate() { return new ExecutionJobRepository.Candidate(jobId,requestId); }
    }
    private CallerIdentity caller(String name) { var u=users.findByUsername(name).orElseThrow();return new CallerIdentity(u.getId(),u.getUsername()); }
    private Request request(Fixture f) { return requests.findById(f.requestId()).orElseThrow(); }
    private ExecutionJob job(Fixture f) { return jobs.findById(f.jobId()).orElseThrow(); }
    private ExecutionLease lease(StartExecutionJob.Claim c) { return new ExecutionLease(c.command().requestId(),c.job().getId(),c.job().getAttemptCount(),c.token()); }
    private ExecutionRecoveryTransactions.Input command(Fixture f) { return new ExecutionRecoveryTransactions.Input(UUID.randomUUID(),request(f).getVersion(),"Investigated attempt"); }
    private java.util.List<ExecutionAttempt.Event> events(Fixture f) { return ledger.events(f.requestId(),f.jobId(),null,100); }
    @BeforeEach void setup() {
        time.set(now);when(clock.instant()).thenAnswer(call->time.get());when(handler.supportsIdempotentRetries()).thenReturn(true);TestProviderContracts.stub(handler);
        when(handler.execute(any())).thenReturn(Map.of("ok",true));when(handler.reconcile(any())).thenReturn(ProviderObservation.unknown());
        scheduler=new ExecutionHeartbeatScheduler(heartbeat,()->Duration.ofMillis(10),clock);
        when(scheduling.start(any(),any())).thenAnswer(call->scheduler.start(call.getArgument(0),call.getArgument(1)));
        for(int i=0;i<30 && !jobs.findCandidates(now,50).isEmpty();i++) worker.execute();
        clearInvocations(handler);
    }
    @AfterEach void shutdown() { scheduler.shutdown(); }
    private Fixture approved() {
        var r=mediator.send(new CreateDraftCommand("EXPENSE",Map.of("amount",100,"description","Attempt ledger"),caller("alice"))).request();
        mediator.send(new SubmitRequestCommand(r.getId(),caller("alice")));
        mediator.send(new DecideStepCommand(r.getId(),DecisionAction.APPROVE,"Reviewed",null,caller("admin")));
        return new Fixture(r.getId(),jobs.findByRequestIdOrderByCreatedAtAsc(r.getId()).getFirst().getId());
    }
    @Test void eachRetryHasItsOwnImmutableAuthorizationAndOutcomeWithStableInput() {
        var f=approved();var first=start.start(f.candidate()).orElseThrow();
        var initial=ledger.find(first.token()).orElseThrow();
        assertEquals(1,initial.number());assertEquals(now,initial.authorizedAt());assertEquals(now.plusSeconds(1800),initial.deadline());
        finish.finish(first,null,new ActionHandler.ActionExecutionException("retry"),true);
        time.set(now.plusSeconds(6));var second=start.start(f.candidate()).orElseThrow();
        var next=ledger.find(second.token()).orElseThrow();
        assertEquals(2,next.number());assertEquals(now.plusSeconds(6),next.authorizedAt());
        assertEquals(initial.input(),next.input());assertEquals(initial.idempotencyKey(),next.idempotencyKey());
        assertEquals(now,job(f).getStartedAt());assertEquals(now,request(f).getExecutionStartedAt());
        finish.finish(second,Map.of("receipt",List.of("immutable")),null,true);
        assertEquals(2,events(f).size());assertTrue(events(f).stream().allMatch(e->e.kind()==ExecutionAttempt.Kind.OUTCOME));
        assertEquals("COMPLETED",events(f).getFirst().outcome());assertEquals("FAILED",events(f).getLast().outcome());
        assertEquals(2,history.attempts(f.requestId(),f.jobId(),null,100,caller("admin")).attempts().size());
        assertThrows(UnsupportedOperationException.class,()->next.input().clear());
    }
    private void fault(String table,String predicate,Runnable body) {
        String fn="attempt_fault_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE FUNCTION "+fn+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'attempt evidence fault' USING ERRCODE='23514'; END; $$");
        jdbc.execute("CREATE TRIGGER "+fn+" BEFORE INSERT ON "+table+" FOR EACH ROW WHEN ("+predicate+") EXECUTE FUNCTION "+fn+"()");
        try { body.run(); } finally {jdbc.execute("DROP TRIGGER "+fn+" ON "+table);jdbc.execute("DROP FUNCTION "+fn+"()");}
    }
    @Test void failedAttemptInsertRollsBackStartAndKeepsCancellationAvailable() {
        var f=approved();long version=request(f).getVersion();
        fault("execution_attempts","NEW.job_id="+f.jobId(),()->assertThrows(org.springframework.dao.DataAccessException.class,()->start.start(f.candidate())));
        assertEquals(JobStatus.PLANNED,job(f).getStatus());assertNull(request(f).getExecutionStartedAt());assertEquals(version,request(f).getVersion());
        assertTrue(ledger.attempts(f.requestId(),f.jobId(),null,100).isEmpty());
        cancel.execute(f.requestId(),caller("alice"));verify(handler,never()).execute(any());
    }
    @Test void failedOutcomeInsertRollsBackBusinessCompletionAndCanBeFinishedOnce() {
        var f=approved();var claim=start.start(f.candidate()).orElseThrow();
        fault("execution_attempt_events","NEW.job_id="+f.jobId()+" AND NEW.kind='OUTCOME'",()->assertThrows(org.springframework.dao.DataAccessException.class,
                ()->finish.finish(claim,Map.of("receipt","external-success"),null,true)));
        assertEquals(JobStatus.STARTED,job(f).getStatus());assertEquals(RequestStatus.APPROVED,request(f).getStatus());assertTrue(events(f).isEmpty());
        assertTrue(finish.finish(claim,Map.of("receipt","external-success"),null,true));
        assertFalse(finish.finish(claim,Map.of("duplicate",true),null,true));assertEquals(1,events(f).size());
    }
    @Test void heartbeatExtendsLiveLeaseWithoutChangingAttemptOrFirstStartAndFinishReloadsIt() {
        var f=approved();var claim=start.start(f.candidate()).orElseThrow();var snapshot=ledger.find(claim.token()).orElseThrow();
        time.set(now.plusSeconds(290));long version=request(f).getVersion();
        assertEquals(RENEWED,heartbeat.renew(lease(claim)));assertEquals(now.plusSeconds(590),job(f).getLeaseExpiresAt());
        assertTrue(request(f).getVersion()>version);assertEquals(snapshot,ledger.find(claim.token()).orElseThrow());
        long renewedVersion=request(f).getVersion();
        assertEquals(UNCHANGED,heartbeat.renew(lease(claim)));assertEquals(renewedVersion,request(f).getVersion());assertEquals(1,events(f).size());
        time.set(now.plusSeconds(310));assertTrue(finish.finish(claim,Map.of("ok",true),null,true));
        assertEquals(LOST,heartbeat.renew(lease(claim)));assertEquals(now,request(f).getExecutionStartedAt());assertEquals(1,job(f).getAttemptCount());
    }
    @Test void staleWrongAndExpiredHeartbeatsCannotResurrectOrAlterClaims() {
        var f=approved();var claim=start.start(f.candidate()).orElseThrow();long version=request(f).getVersion();
        assertEquals(LOST,heartbeat.renew(new ExecutionLease(f.requestId(),f.jobId(),1,UUID.randomUUID())));
        assertEquals(LOST,heartbeat.renew(new ExecutionLease(f.requestId(),f.jobId(),2,claim.token())));
        time.set(now.minusSeconds(1));assertEquals(LOST,heartbeat.renew(lease(claim)));
        time.set(now.plusSeconds(300));assertEquals(LOST,heartbeat.renew(lease(claim)));
        assertEquals(now.plusSeconds(300),job(f).getLeaseExpiresAt());assertEquals(version,request(f).getVersion());assertTrue(events(f).isEmpty());
        assertTrue(start.start(f.candidate()).isEmpty());assertEquals(JobStatus.RECONCILIATION_REQUIRED,job(f).getStatus());
        assertEquals("RECONCILIATION_REQUIRED",events(f).getFirst().outcome());
    }
    @Test void heartbeatCannotExtendPastTheImmutableMaximumAttemptDeadline() {
        var f=approved();var claim=start.start(f.candidate()).orElseThrow();
        for(int elapsed:new int[]{250,500,750,1000,1250,1500}) {
            time.set(now.plusSeconds(elapsed));assertEquals(RENEWED,heartbeat.renew(lease(claim)));
        }
        assertEquals(now.plusSeconds(1800),job(f).getLeaseExpiresAt());
        time.set(now.plusSeconds(1750));assertEquals(UNCHANGED,heartbeat.renew(lease(claim)));
        time.set(now.plusSeconds(1800));assertEquals(LOST,heartbeat.renew(lease(claim)));
        assertFalse(finish.finish(claim,Map.of("late",true),null,true));assertEquals(JobStatus.RECONCILIATION_REQUIRED,job(f).getStatus());
    }
    @Test void busyParentSkipsHeartbeatWithoutWaitingAndFinishPreventsFurtherRenewal() throws Exception {
        var f=approved();var claim=start.start(f.candidate()).orElseThrow();time.set(now.plusSeconds(100));
        try(var pool=Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactions).executeWithoutResult(status->{
                requestWriteLock.findByIdForUpdate(f.requestId()).orElseThrow();
                try {assertEquals(BUSY,pool.submit(()->heartbeat.renew(lease(claim))).get(3,TimeUnit.SECONDS));}
                catch(Exception e) {throw new AssertionError(e);}
            });
        }
        assertTrue(events(f).isEmpty());finish.finish(claim,Map.of("done",true),null,true);
        assertEquals(LOST,heartbeat.renew(lease(claim)));assertEquals(1,events(f).size());
    }
    @Test void heartbeatEvidenceFailureRollsBackLeaseAndAggregateVersion() {
        var f=approved();var claim=start.start(f.candidate()).orElseThrow();long version=request(f).getVersion();time.set(now.plusSeconds(290));
        fault("execution_attempt_events","NEW.job_id="+f.jobId()+" AND NEW.kind='HEARTBEAT'",()->assertThrows(org.springframework.dao.DataAccessException.class,()->heartbeat.renew(lease(claim))));
        assertEquals(now.plusSeconds(300),job(f).getLeaseExpiresAt());assertEquals(version,request(f).getVersion());assertTrue(events(f).isEmpty());
    }
    @Test void renewedLeaseBlocksReconciliationUntilExpiryAndProviderEvidenceDoesNotRewriteOutcome() {
        var f=approved();var claim=start.start(f.candidate()).orElseThrow();time.set(now.plusSeconds(290));heartbeat.renew(lease(claim));
        time.set(now.plusSeconds(310));
        assertEquals("execution-attempt-active",assertThrows(ConflictException.class,()->recovery.reconcile(f.requestId(),f.jobId(),command(f),caller("admin"))).getCode());
        time.set(now.plusSeconds(591));
        recovery.reconcile(f.requestId(),f.jobId(),command(f),caller("admin"));
        var original=events(f).stream().filter(e->e.kind()==ExecutionAttempt.Kind.OUTCOME).findFirst().orElseThrow();
        when(handler.reconcile(any())).thenReturn(new ProviderObservation(ProviderObservation.Outcome.SUCCEEDED,"provider:confirmed",Map.of("ok",true)));
        var result=recovery.reconcile(f.requestId(),f.jobId(),command(f),caller("admin"));
        assertEquals(original,events(f).stream().filter(e->e.kind()==ExecutionAttempt.Kind.OUTCOME).findFirst().orElseThrow());
        var observation=events(f).getFirst();assertEquals(ExecutionAttempt.Kind.PROVIDER_OBSERVATION,observation.kind());
        assertEquals(result.entry().command().id(),observation.recoveryCommandId());assertEquals(1,observation.attemptNumber());
        assertEquals(JobStatus.COMPLETED,job(f).getStatus());assertEquals(LOST,heartbeat.renew(lease(claim)));
    }
    @Test void staleHeartbeatCannotRenewARecoveryRetryAndLateProviderObservationTargetsOriginalAttempt() {
        var f=approved();var first=start.start(f.candidate()).orElseThrow();finish.finish(first,null,new RuntimeException("uncertain"),false);
        var old=command(f);recoveryTx.begin(f.requestId(),f.jobId(),old,caller("admin"));
        time.set(now.plusSeconds(61));recovery.reconcile(f.requestId(),f.jobId(),command(f),caller("admin"));
        recovery.retry(f.requestId(),f.jobId(),command(f),caller("admin"));var second=start.start(f.candidate()).orElseThrow();
        time.set(now.plusSeconds(250));assertEquals(LOST,heartbeat.renew(lease(first)));assertEquals(RENEWED,heartbeat.renew(lease(second)));
        recoveryTx.finish(old.commandId(),new ProviderObservation(ProviderObservation.Outcome.SUCCEEDED,"provider:late",Map.of()),null);
        var stale=events(f).getFirst();assertEquals(1,stale.attemptNumber());assertEquals("STALE",stale.outcome());assertEquals(first.token(),stale.attemptToken());
        assertEquals(JobStatus.STARTED,job(f).getStatus());assertEquals(second.token(),job(f).getClaimToken());
    }
    @Test void workerRenewsInIndependentTransactionsWhileProviderRunsAndStopsBeforeFinish() throws Exception {
        var f=approved();var committed=new CountDownLatch(1);
        new TransactionTemplate(transactions).executeWithoutResult(status->doAnswer(call->{
            call.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){committed.countDown();}});
            return null;
        }).when(recorder).heartbeat(argThat(j->j!=null && j.getId().equals(f.jobId()))));
        when(handler.execute(argThat(c->c!=null && c.requestId().equals(f.requestId())))).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertNotNull(request(f).getExecutionStartedAt());
            time.set(now.plusSeconds(290));assertTrue(committed.await(5,TimeUnit.SECONDS));
            assertEquals(now.plusSeconds(590),job(f).getLeaseExpiresAt());return Map.of("heartbeat","committed");
        });
        try(var pool=Executors.newSingleThreadExecutor()) {pool.submit(()->worker.execute()).get(10,TimeUnit.SECONDS);}
        assertEquals(JobStatus.COMPLETED,job(f).getStatus());
        assertEquals(1,events(f).stream().filter(e->e.kind()==ExecutionAttempt.Kind.HEARTBEAT).count());
        assertEquals(1,events(f).stream().filter(e->e.kind()==ExecutionAttempt.Kind.OUTCOME).count());
    }
    @Test void databaseRejectsMissingRenewalEvidenceAndImmutableHistoryChanges() {
        var f=approved();var claim=start.start(f.candidate()).orElseThrow();
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE execution_jobs SET lease_expires_at=lease_expires_at+interval '1 second' WHERE id=?",f.jobId()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE execution_attempts SET deadline=deadline+interval '1 hour' WHERE claim_token=?",claim.token()));
        time.set(now.plusSeconds(10));heartbeat.renew(lease(claim));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("DELETE FROM execution_attempt_events WHERE job_id=?",f.jobId()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("TRUNCATE execution_attempts CASCADE"));
    }
    @Test void expiredHeartbeatRegistrationDoesNotInvokeProviderOrInventAnOutcome() {
        var f=approved();
        doAnswer(call->{
            Instant deadline=call.getArgument(1);time.set(deadline);
            return scheduler.start(call.getArgument(0),deadline);
        }).when(scheduling).start(any(),any());
        worker.execute();
        assertEquals(JobStatus.STARTED,job(f).getStatus());assertTrue(events(f).isEmpty());
        assertNotNull(ledger.find(job(f).getClaimToken()).orElseThrow());
        verify(handler,never()).execute(any());
        assertTrue(start.start(f.candidate()).isEmpty());assertEquals(JobStatus.RECONCILIATION_REQUIRED,job(f).getStatus());
        assertEquals(1,events(f).size());
    }
    @Test void historyApiIsScopedBoundedAndOmitsClaimTokensAndFrozenInputs() throws Exception {
        var f=approved();var first=start.start(f.candidate()).orElseThrow();finish.finish(first,null,new RuntimeException("retry"),true);
        time.set(now.plusSeconds(6));var second=start.start(f.candidate()).orElseThrow();finish.finish(second,Map.of("ok",true),null,true);
        var operator=caller("admin");var auth=new org.springframework.security.authentication.TestingAuthenticationToken(new UserPrincipal(operator.id(),operator.username(),Set.of("ADMIN"),null),null,"ROLE_TEST");
        String path="/api/v1/requests/"+f.requestId()+"/execution-recovery/"+f.jobId();
        mvc.perform(get(path+"/attempts?limit=1").with(authentication(auth))).andExpect(status().isOk())
                .andExpect(jsonPath("$.attempts.length()").value(1)).andExpect(jsonPath("$.attempts[0].number").value(2))
                .andExpect(jsonPath("$.attempts[0].token").doesNotExist()).andExpect(jsonPath("$.attempts[0].input").doesNotExist());
        mvc.perform(get(path+"/attempts?beforeAttempt=2").with(authentication(auth))).andExpect(status().isOk()).andExpect(jsonPath("$.attempts[0].number").value(1));
        mvc.perform(get(path+"/attempt-events?limit=1").with(authentication(auth))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attemptNumber").value(2)).andExpect(jsonPath("$[0].attemptToken").doesNotExist());
        mvc.perform(get(path+"/attempts?limit=101").with(authentication(auth))).andExpect(status().isBadRequest());
        var owner=caller("alice");var denied=new org.springframework.security.authentication.TestingAuthenticationToken(new UserPrincipal(owner.id(),owner.username(),Set.of("ADMIN"),null),null,"ROLE_TEST");
        mvc.perform(get(path+"/attempts").with(authentication(denied))).andExpect(status().isForbidden());
        mvc.perform(get(path+"/attempt-events").with(authentication(denied))).andExpect(status().isForbidden());
        var firstEvent=events(f).getFirst();assertEquals(1,history.events(f.requestId(),f.jobId(),firstEvent.sequence(),100,operator).getFirst().attemptNumber());
    }
}
