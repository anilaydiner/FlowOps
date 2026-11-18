package com.flowops;

import com.flowops.application.execution.*;
import com.flowops.application.execution.port.*;
import com.flowops.application.mediator.Mediator;
import com.flowops.application.request.command.*;
import com.flowops.application.request.lifecycle.CancelRequest;
import com.flowops.domain.execution.*;
import com.flowops.domain.execution.port.*;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.port.UserRepository;
import com.flowops.domain.organization.port.DepartmentRepository;
import com.flowops.domain.request.*;
import com.flowops.domain.request.port.RequestRepository;
import com.flowops.domain.workflow.*;
import com.flowops.domain.workflow.port.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.flowops.application.execution.ReconcileExecutionPlan.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompletionReconciliationTest extends AbstractIntegrationTest {
    @MockitoBean ActionHandler handler;
    @MockitoBean Clock clock;
    @MockitoSpyBean CompleteRequestExecution completion;
    @Autowired ReconcileExecutionPlan processor;
    @Autowired com.flowops.application.audit.AuditService audit;
    @Autowired ExecutionPlanReconciliationRepository evidence;
    @Autowired com.flowops.application.request.port.RequestExecutionQueries progress;
    @Autowired Mediator mediator;
    @Autowired UserRepository users;
    @Autowired RequestRepository requests;
    @Autowired ExecutionJobRepository jobs;
    @Autowired ExecutionPlanRepository plans;
    @Autowired WorkflowRepository workflows;
    @Autowired WorkflowStepRepository definitions;
    @Autowired RequestTypeRepository types;
    @Autowired DepartmentRepository departments;
    @Autowired StartExecutionJob start;
    @Autowired FinishExecutionJob finish;
    @Autowired ProcessExecutionJobs worker;
    @Autowired CancelRequest cancel;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private final Instant now=Instant.parse("2026-12-07T12:00:00Z");
    private TransactionTemplate tx(){return new TransactionTemplate(transactions);}
    private CallerIdentity caller(String name){var u=users.findByUsername(name).orElseThrow();return new CallerIdentity(u.getId(),u.getUsername());}
    private Request request(Long id){return requests.findById(id).orElseThrow();}
    private List<ExecutionJob> rows(Long id){return jobs.findByRequestIdOrderByCreatedAtAsc(id);}
    private int facts(Long id,String type,String action){return jdbc.queryForObject("SELECT count(*) FROM lifecycle_audit_events WHERE request_id=? AND event_type=? AND action=?",Integer.class,id,type,action);}
    private void completionEnabled(boolean enabled){tx().executeWithoutResult(s->{if(enabled)doCallRealMethod().when(completion).completeLocked(any(),any());else doNothing().when(completion).completeLocked(any(),any());});}
    @BeforeEach void setup(){
        when(clock.instant()).thenReturn(now);when(handler.supportsIdempotentRetries()).thenReturn(true);TestProviderContracts.stub(handler);
        when(handler.execute(any())).thenReturn(Map.of("ok",true));when(handler.reconcile(any())).thenReturn(ProviderObservation.unknown());completionEnabled(true);
        for(int i=0;i<30 && !jobs.findCandidates(now,50).isEmpty();i++)worker.execute();clearInvocations(handler);
    }
    private Request approved(List<String> actions){
        String code="CR_"+UUID.randomUUID().toString().replace("-","").substring(0,20).toUpperCase(Locale.ROOT);
        tx().executeWithoutResult(s->{var wf=workflows.save(new Workflow("WF_"+code,"Reconciliation",null));definitions.save(new WorkflowStep(wf,1,"REVIEW","Review","ADMIN"));
            var type=new RequestType(code,"Reconciliation",null,departments.findByCode("FINANCE").orElseThrow());type.setDefaultWorkflow(wf);type.setExecutionActions(actions);types.save(type);});
        var draft=mediator.send(new CreateDraftCommand(code,Map.of("amount",100,"description","Frozen restoration"),caller("alice"))).request();
        mediator.send(new SubmitRequestCommand(draft.getId(),caller("alice")));
        return mediator.send(new DecideStepCommand(draft.getId(),DecisionAction.APPROVE,"Reviewed",null,caller("admin"))).request();
    }
    private StartExecutionJob.Claim claim(ExecutionJob job){return start.start(new ExecutionJobRepository.Candidate(job.getId(),job.getRequestId())).orElseThrow();}
    // Controlled administrative-loss fixtures only. Application recovery never disables guards or updates evidence.
    private void administrative(Runnable operation){tx().executeWithoutResult(s->{jdbc.execute("SET LOCAL session_replication_role=replica");operation.run();jdbc.execute("SET LOCAL session_replication_role=origin");});}
    private Map<String,Object> snapshot(Long jobId){return new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM execution_jobs WHERE id=?",jobId));}
    private void lose(ExecutionPlan p,ExecutionJob job,boolean binding,boolean row){administrative(()->{
        if(binding)jdbc.update("DELETE FROM execution_plan_job_bindings WHERE plan_id=? AND action=?",p.id(),job.getAction());
        if(row)jdbc.update("DELETE FROM execution_jobs WHERE id=?",job.getId());
    });}
    private void restoreSnapshot(ExecutionPlan p,ExecutionJob job,Map<String,Object> original){administrative(()->{
        jdbc.update("INSERT INTO execution_jobs("+String.join(",",original.keySet())+") OVERRIDING SYSTEM VALUE VALUES ("+String.join(",",Collections.nCopies(original.size(),"?"))+") ON CONFLICT DO NOTHING",original.values().toArray());
        jdbc.update("INSERT INTO execution_plan_job_bindings(plan_id,action,job_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",p.id(),job.getAction(),job.getId());
    });}
    @Test void strandedCompletedJobsFinalizeOnceAndRepeatedScansDoNotChangeVersion(){
        completionEnabled(false);var r=approved(List.of(JobActions.ACCOUNTING_EXPORT));var c=claim(rows(r.getId()).getFirst());finish.finish(c,Map.of("receipt","done"),null,true);
        assertEquals(RequestStatus.APPROVED,request(r.getId()).getStatus());completionEnabled(true);
        assertEquals(COMPLETED,processor.process(r.getId()).outcome());assertEquals(RequestStatus.EXECUTED,request(r.getId()).getStatus());
        long version=request(r.getId()).getVersion();assertEquals(SKIPPED,processor.process(r.getId()).outcome());assertEquals(version,request(r.getId()).getVersion());
        assertEquals(1,facts(r.getId(),"EXECUTION","COMPLETED"));assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM outbox WHERE idempotency_key LIKE ? OR idempotency_key LIKE ?",Integer.class,"req:"+r.getId()+":EXECUTED:%","req:"+r.getId()+":NOTIFY_EXECUTED:%"));
        verify(handler,never()).execute(any());
    }
    @Test void deliberatelyEmptyStrandedPlanCompletesWithoutFabricatingAStart(){
        completionEnabled(false);var r=approved(List.of());assertEquals(RequestStatus.APPROVED,r.getStatus());completionEnabled(true);
        var pending=progress.findByRequestIds(List.of(r.getId())).get(r.getId());assertEquals(com.flowops.application.request.model.ExecutionProgress.State.COMPLETION_PENDING,pending.state());assertEquals(0,pending.expectedJobs());assertFalse(pending.requiresAttention());
        assertEquals(COMPLETED,processor.process(r.getId()).outcome());assertNull(request(r.getId()).getExecutionStartedAt());assertTrue(rows(r.getId()).isEmpty());
        assertEquals(1,facts(r.getId(),"EXECUTION","COMPLETED"));verify(handler,never()).execute(any());
    }
    @Test void neverStartedRowRestoresOriginalIdInputBudgetAndCreationTimeAfterTypeEdits(){
        var r=approved(List.of(JobActions.ACCOUNTING_EXPORT));var p=plans.findByRequestId(r.getId()).orElseThrow();var original=rows(r.getId()).getFirst();
        lose(p,original,false,true);jdbc.update("UPDATE request_types SET execution_actions='[\"PURCHASE_TICKET\"]'::jsonb WHERE id=?",p.typeId());
        long before=request(r.getId()).getVersion();var result=processor.process(r.getId());assertEquals(REPAIRED,result.outcome());assertEquals(1,result.restored());
        var restored=rows(r.getId()).getFirst();assertEquals(original.getId(),restored.getId());assertEquals(original.getCreatedAt(),restored.getCreatedAt());
        assertEquals(original.getInputPayload(),restored.getInputPayload());assertEquals(original.getIdempotencyKey(),restored.getIdempotencyKey());assertEquals(original.getMaxRetries(),restored.getMaxRetries());
        assertEquals(JobStatus.PLANNED,restored.getStatus());assertEquals(0,restored.getAttemptCount());assertEquals(0,restored.getRetryCount());assertNull(restored.getStartedAt());
        assertTrue(request(r.getId()).getVersion()>before);long version=request(r.getId()).getVersion();assertEquals(UNCHANGED,processor.process(r.getId()).outcome());assertEquals(version,request(r.getId()).getVersion());
        assertEquals(1,facts(r.getId(),"JOB","RESTORED_FROM_PLAN"));assertEquals(1,facts(r.getId(),"JOB","PLANNED"));verify(handler,never()).execute(any());
        worker.execute();assertEquals(RequestStatus.EXECUTED,request(r.getId()).getStatus());
    }
    @Test void missingRowAndBindingCanBeRecoveredWithoutClearingAnotherActionsStartLatch(){
        var r=approved(List.of(JobActions.ACCOUNTING_EXPORT,JobActions.PROVISIONING));var p=plans.findByRequestId(r.getId()).orElseThrow();var list=rows(r.getId());
        var first=claim(list.getFirst());finish.finish(first,Map.of("ok",true),null,true);Instant latch=request(r.getId()).getExecutionStartedAt();
        lose(p,list.get(1),true,true);assertEquals(REPAIRED,processor.process(r.getId()).outcome());assertEquals(latch,request(r.getId()).getExecutionStartedAt());
        assertEquals(JobStatus.COMPLETED,jobs.findById(list.getFirst().getId()).orElseThrow().getStatus());assertEquals(JobStatus.PLANNED,jobs.findById(list.get(1).getId()).orElseThrow().getStatus());
        assertTrue(plans.jobsMatch(p.id()));assertEquals(RequestStatus.APPROVED,request(r.getId()).getStatus());verify(handler,never()).execute(any());
    }
    @Test void lostBindingToACompletedOriginalRowRepairsTheLinkAndThenCompletes(){
        completionEnabled(false);var r=approved(List.of(JobActions.ACCOUNTING_EXPORT));var p=plans.findByRequestId(r.getId()).orElseThrow();var original=rows(r.getId()).getFirst();
        var c=claim(original);finish.finish(c,Map.of("receipt","complete"),null,true);completionEnabled(true);
        var completed=jobs.findById(original.getId()).orElseThrow();lose(p,completed,true,false);var result=processor.process(r.getId());
        assertEquals(COMPLETED,result.outcome());assertEquals(1,result.rebound());assertEquals(0,result.restored());
        assertEquals(completed.getResult(),jobs.findById(original.getId()).orElseThrow().getResult());assertEquals(completed.getAttemptCount(),jobs.findById(original.getId()).orElseThrow().getAttemptCount());
        assertEquals(1,facts(r.getId(),"EXECUTION_PLAN","BINDING_RESTORED"));assertEquals(1,facts(r.getId(),"EXECUTION","COMPLETED"));
    }
    @Test void missingStartedJobIsNeverRecreatedAndAttentionIsDeduplicated(){
        var r=approved(List.of(JobActions.ACCOUNTING_EXPORT));var p=plans.findByRequestId(r.getId()).orElseThrow();var c=claim(rows(r.getId()).getFirst());
        var started=jobs.findById(c.job().getId()).orElseThrow();var backup=snapshot(started.getId());lose(p,started,false,true);
        try{
            var result=processor.process(r.getId());assertEquals(ATTENTION,result.outcome());assertEquals("MISSING_JOB_HAS_EXECUTION_HISTORY",result.issues().getFirst().code());
            assertTrue(jobs.findById(started.getId()).isEmpty());long version=request(r.getId()).getVersion();processor.process(r.getId());assertEquals(version,request(r.getId()).getVersion());
            assertEquals(1,facts(r.getId(),"EXECUTION_PLAN","RECONCILIATION_REQUIRED"));assertEquals(RequestStatus.APPROVED,request(r.getId()).getStatus());verify(handler,never()).execute(any());
            var findings=audit.timeline(r.getId(),null,null,"execution_plan",caller("admin"));assertEquals(1,findings.size());
            assertEquals("SYSTEM",findings.getFirst().details().get("actorKind"));assertEquals("MISSING_JOB_HAS_EXECUTION_HISTORY",findings.getFirst().details().get("reasonCode"));
        }finally{restoreSnapshot(p,started,backup);finish.finish(c,null,new RuntimeException("uncertain"),false);}
    }
    @Test void deletingReceiptsDoesNotEraseMaterializationHistoryOrAuthorizeANewJob(){
        var r=approved(List.of(JobActions.ACCOUNTING_EXPORT));var p=plans.findByRequestId(r.getId()).orElseThrow();var original=rows(r.getId()).getFirst();var backup=snapshot(original.getId());
        var receipt=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM execution_plan_materializations WHERE plan_id=? AND action=?",p.id(),original.getAction()));
        lose(p,original,true,true);administrative(()->jdbc.update("DELETE FROM execution_plan_materializations WHERE plan_id=?",p.id()));
        try{
            var result=processor.process(r.getId());assertEquals(ATTENTION,result.outcome());assertEquals("UNVERIFIABLE_MATERIALIZATION",result.issues().getFirst().code());assertTrue(rows(r.getId()).isEmpty());
        }finally{restoreSnapshot(p,original,backup);administrative(()->jdbc.update("INSERT INTO execution_plan_materializations("+String.join(",",receipt.keySet())+") VALUES ("+String.join(",",Collections.nCopies(receipt.size(),"?"))+")",receipt.values().toArray()));}
    }
    @Test void busyParentIsSkippedAndTheSameWorkCanBeRecoveredOnTheNextSweep()throws Exception{
        var r=approved(List.of(JobActions.ACCOUNTING_EXPORT));var p=plans.findByRequestId(r.getId()).orElseThrow();var original=rows(r.getId()).getFirst();lose(p,original,false,true);
        try(var pool=Executors.newSingleThreadExecutor()){
            tx().executeWithoutResult(s->{requests.findByIdForUpdate(r.getId()).orElseThrow();try{assertEquals(BUSY,pool.submit(()->processor.process(r.getId())).get(3,TimeUnit.SECONDS).outcome());}catch(Exception e){throw new AssertionError(e);}});
        }
        assertEquals(REPAIRED,processor.process(r.getId()).outcome());
    }
    @Test void cancellationWinnerIsNeverReopenedOrRepaired(){
        var r=approved(List.of(JobActions.ACCOUNTING_EXPORT));var p=plans.findByRequestId(r.getId()).orElseThrow();cancel.execute(r.getId(),caller("alice"));var stopped=rows(r.getId()).getFirst();var backup=snapshot(stopped.getId());
        lose(p,stopped,false,true);
        try{assertEquals(SKIPPED,processor.process(r.getId()).outcome());assertTrue(rows(r.getId()).isEmpty());assertEquals(RequestStatus.CANCELLED,request(r.getId()).getStatus());}
        finally{restoreSnapshot(p,stopped,backup);}
    }
    @Test void lastFinishRacingTheScannerProducesOneCompletionFactAndNotificationPair()throws Exception{
        var r=approved(List.of(JobActions.ACCOUNTING_EXPORT));var c=claim(rows(r.getId()).getFirst());var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var finishing=pool.submit(()->{ready.countDown();go.await();return finish.finish(c,Map.of("receipt","done"),null,true);});
            var scanning=pool.submit(()->{ready.countDown();go.await();return processor.process(r.getId());});
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();assertTrue(finishing.get(10,TimeUnit.SECONDS));scanning.get(10,TimeUnit.SECONDS);
        }
        assertEquals(SKIPPED,processor.process(r.getId()).outcome());assertEquals(RequestStatus.EXECUTED,request(r.getId()).getStatus());
        assertEquals(1,facts(r.getId(),"EXECUTION","COMPLETED"));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM outbox WHERE idempotency_key LIKE ? OR idempotency_key LIKE ?",Integer.class,"req:"+r.getId()+":EXECUTED:%","req:"+r.getId()+":NOTIFY_EXECUTED:%"));
    }
    @Test void existingUncertainJobIsPreservedWithoutRetryAndUnknownParentIsSkipped(){
        var r=approved(List.of(JobActions.ACCOUNTING_EXPORT));var c=claim(rows(r.getId()).getFirst());finish.finish(c,null,new RuntimeException("uncertain"),false);
        var original=snapshot(c.job().getId());long version=request(r.getId()).getVersion();
        assertEquals(UNCHANGED,processor.process(r.getId()).outcome());assertEquals(original,snapshot(c.job().getId()));assertEquals(version,request(r.getId()).getVersion());
        jdbc.update("UPDATE requests SET execution_start_unknown=true WHERE id=?",r.getId());
        assertEquals(SKIPPED,processor.process(r.getId()).outcome());assertEquals(RequestStatus.APPROVED,request(r.getId()).getStatus());verify(handler,never()).execute(any());
    }
    private void fault(Long id,String type,String action,Runnable operation){
        String fn="reconcile_fault_"+UUID.randomUUID().toString().replace("-","");jdbc.execute("CREATE FUNCTION "+fn+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'reconciliation evidence fault' USING ERRCODE='23514'; END; $$");
        jdbc.execute("CREATE TRIGGER "+fn+" BEFORE INSERT ON lifecycle_audit_events FOR EACH ROW WHEN (NEW.request_id="+id+" AND NEW.event_type='"+type+"' AND NEW.action='"+action+"') EXECUTE FUNCTION "+fn+"()");
        try{operation.run();}finally{jdbc.execute("DROP TRIGGER "+fn+" ON lifecycle_audit_events");jdbc.execute("DROP FUNCTION "+fn+"()");}
    }
    @Test void failedRepairAuditRollsBackTheRestoredRowBindingAndParentVersion(){
        var r=approved(List.of(JobActions.ACCOUNTING_EXPORT));var p=plans.findByRequestId(r.getId()).orElseThrow();var original=rows(r.getId()).getFirst();lose(p,original,true,true);long version=request(r.getId()).getVersion();
        fault(r.getId(),"JOB","RESTORED_FROM_PLAN",()->assertThrows(org.springframework.dao.DataAccessException.class,()->processor.process(r.getId())));
        assertTrue(rows(r.getId()).isEmpty());assertFalse(evidence.bindings(p.id()).containsKey(original.getAction()));assertEquals(version,request(r.getId()).getVersion());
        assertEquals(REPAIRED,processor.process(r.getId()).outcome());
    }
    @Test void completionFailureForOneRequestCannotRollBackAnotherRequestsCommittedCompletion(){
        completionEnabled(false);var a=approved(List.of());var b=approved(List.of());completionEnabled(true);
        var queue=mock(ExecutionPlanReconciliationRepository.class);when(queue.highWatermark()).thenReturn(b.getId());when(queue.candidates(anyLong(),anyLong(),anyInt())).thenReturn(List.of(a.getId(),b.getId()));
        var scanner=new ReconcileExecutionPlans(queue,processor,2);
        fault(a.getId(),"EXECUTION","COMPLETED",()->{var result=scanner.scan();assertEquals(1,result.failures());assertEquals(1,result.completed());});
        assertEquals(RequestStatus.APPROVED,request(a.getId()).getStatus());assertEquals(RequestStatus.EXECUTED,request(b.getId()).getStatus());
        assertEquals(COMPLETED,processor.process(a.getId()).outcome());assertEquals(1,facts(b.getId(),"EXECUTION","COMPLETED"));
    }
    @Test void perRequestCommitSurvivesRollbackOfAnAmbientCallerTransaction(){
        completionEnabled(false);var r=approved(List.of());completionEnabled(true);
        tx().executeWithoutResult(s->{assertEquals(COMPLETED,processor.process(r.getId()).outcome());s.setRollbackOnly();});
        assertEquals(RequestStatus.EXECUTED,request(r.getId()).getStatus());
    }
    @Test void legacyApprovalWithoutManifestIsSkippedAndReceiptsAreImmutable(){
        var legacy=mediator.send(new CreateDraftCommand("EXPENSE",Map.of("amount",100,"description","Legacy"),caller("alice"))).request();
        tx().executeWithoutResult(s->{var r=requests.findByIdForUpdate(legacy.getId()).orElseThrow();r.markSubmitted(now);requests.save(r);LegacyRequestFixtures.approveWithoutManifest(jdbc,r.getId(),now,true);});
        assertEquals(SKIPPED,processor.process(legacy.getId()).outcome());assertEquals(RequestStatus.APPROVED,request(legacy.getId()).getStatus());assertTrue(plans.findByRequestId(legacy.getId()).isEmpty());
        var r=approved(List.of(JobActions.ACCOUNTING_EXPORT));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("DELETE FROM execution_plan_materializations WHERE plan_id=?",r.getExecutionPlanId()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE execution_plan_materializations SET unstarted_at_capture=false WHERE plan_id=?",r.getExecutionPlanId()));
    }
}
