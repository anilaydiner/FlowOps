package com.flowops;

import com.flowops.api.dto.RequestResponse;
import com.flowops.application.execution.*;
import com.flowops.application.mediator.Mediator;
import com.flowops.application.request.command.*;
import com.flowops.application.request.model.ExecutionProgress;
import com.flowops.application.request.port.RequestExecutionQueries;
import com.flowops.application.request.query.*;
import com.flowops.application.request.sla.ReconcileReviewRequest;
import com.flowops.domain.execution.*;
import com.flowops.domain.execution.port.*;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.port.UserRepository;
import com.flowops.domain.organization.port.DepartmentRepository;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.request.*;
import com.flowops.domain.request.port.RequestRepository;
import com.flowops.domain.request.query.RequestFilter;
import com.flowops.domain.workflow.*;
import com.flowops.domain.workflow.port.*;
import com.flowops.security.UserPrincipal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.flowops.application.request.model.ExecutionProgress.AttentionReason.*;
import static com.flowops.application.request.model.ExecutionProgress.State.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class RequestOutcomesAndProgressTest extends AbstractIntegrationTest {
    @MockitoBean ActionHandler handler;
    @MockitoBean Clock clock;
    @MockitoSpyBean RequestExecutionQueries progress;
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
    @Autowired ReconcileExecutionPlan reconcile;
    @Autowired ReconcileReviewRequest review;
    @Autowired com.flowops.application.request.ClarificationService clarifications;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    private final Instant now=Instant.parse("2026-12-09T12:00:00Z");
    private TransactionTemplate tx(){return new TransactionTemplate(transactions);}
    private void at(long seconds){when(clock.instant()).thenReturn(now.plusSeconds(seconds));}
    @BeforeEach void setup(){at(0);TestProviderContracts.stub(handler);when(handler.supportsIdempotentRetries()).thenReturn(true);}
    private CallerIdentity caller(String name){var u=users.findByUsername(name).orElseThrow();return new CallerIdentity(u.getId(),u.getUsername());}
    private org.springframework.test.web.servlet.request.RequestPostProcessor auth(String name){var c=caller(name);return authentication(new org.springframework.security.authentication.TestingAuthenticationToken(new UserPrincipal(c.id(),c.username(),Set.of(),null),null,"ROLE_TEST"));}
    private Request request(Long id){return requests.findById(id).orElseThrow();}
    private List<ExecutionJob> rows(Long id){return jobs.findByRequestIdOrderByCreatedAtAsc(id);}
    private RequestResponse detail(Long id){return RequestResponse.from(mediator.send(new GetRequest(id,caller("alice"))));}
    private Request submitted(List<String> actions,String role,boolean skipped,Integer expiry){
        String code="OUT_"+UUID.randomUUID().toString().replace("-","").substring(0,20).toUpperCase(Locale.ROOT);
        tx().executeWithoutResult(s->{
            var wf=workflows.save(new Workflow("WF_"+code,"Outcomes",null));var step=new WorkflowStep(wf,1,"REVIEW","Review",role);
            step.configure(CompletionRule.ANY_OF,1,skipped?Map.of("field","amount","op",">","value",9999):Map.of(),null,null,false);step.setExpiryMinutes(expiry);definitions.save(step);
            var type=new RequestType(code,"Outcomes",null,departments.findByCode("FINANCE").orElseThrow());type.setDefaultWorkflow(wf);type.setExecutionActions(actions);types.save(type);
        });
        var draft=mediator.send(new CreateDraftCommand(code,Map.of("amount",100,"description","Milestones"),caller("alice"))).request();
        return mediator.send(new SubmitRequestCommand(draft.getId(),caller("alice"))).request();
    }
    private Request approved(List<String> actions){var r=submitted(actions,"ADMIN",false,null);return mediator.send(new DecideStepCommand(r.getId(),DecisionAction.APPROVE,"Reviewed",null,caller("admin"))).request();}
    private StartExecutionJob.Claim claim(ExecutionJob job){return start.start(new ExecutionJobRepository.Candidate(job.getId(),job.getRequestId())).orElseThrow();}
    private ExecutionProgress summary(Long id){return detail(id).executionProgress();}
    @Test void multiActionExecutionAndClosureRetainOutcomeMilestonesAndExposeTheSameProgressEverywhere()throws Exception{
        var r=approved(List.of(JobActions.ACCOUNTING_EXPORT,JobActions.PROVISIONING));Long id=r.getId();var list=rows(id);
        assertEquals(QUEUED,detail(id).executionState());assertEquals(2,summary(id).expectedJobs());assertEquals(2,summary(id).jobs().planned());assertEquals(now,r.getApprovedAt());assertNull(r.getOutcome());
        at(1);var first=claim(list.getFirst());assertEquals(RUNNING,summary(id).state());assertEquals(1,summary(id).jobs().running());
        at(2);finish.finish(first,Map.of("receipt","first"),null,true);assertEquals(QUEUED,summary(id).state());assertEquals(1,summary(id).jobs().completed());
        at(3);var last=claim(list.get(1));at(4);finish.finish(last,Map.of("receipt","last"),null,true);
        at(10);mvc.perform(post("/api/v1/requests/"+id+"/close").with(auth("alice")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED")).andExpect(jsonPath("$.outcome").value("EXECUTED"))
                .andExpect(jsonPath("$.approvedAt").value(now.toString())).andExpect(jsonPath("$.executedAt").value(now.plusSeconds(4).toString()))
                .andExpect(jsonPath("$.closedAt").value(now.plusSeconds(10).toString())).andExpect(jsonPath("$.executionState").value("COMPLETED"))
                .andExpect(jsonPath("$.executionProgress.expectedJobs").value(2)).andExpect(jsonPath("$.executionProgress.jobs.completed").value(2)).andExpect(jsonPath("$.requiresAttention").value(false));
        assertEquals(now,r.getExecutionPlannedAt());assertEquals(now.plusSeconds(1),request(id).getExecutionStartedAt());
        assertEquals("admin",plans.findByRequestId(id).orElseThrow().approval().actorName());
        assertEquals("alice",jdbc.queryForObject("SELECT actor_name FROM lifecycle_audit_events WHERE request_id=? AND event_type='STATUS' AND after_data->>'status'='CLOSED'",String.class,id));
        var page=mediator.send(new ListRequests(new RequestFilter(null,r.getType().getCode(),null),PageQuery.first(10),caller("alice")));
        var item=RequestResponse.from(page.content().getFirst());assertEquals(detail(id).executionProgress(),item.executionProgress());assertEquals(RequestOutcome.EXECUTED,item.outcome());
        mvc.perform(get("/api/v1/requests").param("typeCode",r.getType().getCode()).with(auth("alice"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].outcome").value("EXECUTED")).andExpect(jsonPath("$.content[0].closedAt").value(now.plusSeconds(10).toString()));
        long version=request(id).getVersion();mvc.perform(post("/api/v1/requests/"+id+"/close").with(auth("alice"))).andExpect(status().isConflict());assertEquals(version,request(id).getVersion());
    }
    @Test void rejectedClosureRetainsRejectionWithoutInventingApprovalOrExecution()throws Exception{
        var r=submitted(List.of(JobActions.ACCOUNTING_EXPORT),"ADMIN",false,null);at(2);
        mediator.send(new DecideStepCommand(r.getId(),DecisionAction.REJECT,"Declined",null,caller("admin")));at(10);
        mvc.perform(post("/api/v1/requests/"+r.getId()+"/close").with(auth("alice"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REJECTED")).andExpect(jsonPath("$.rejectedAt").value(now.plusSeconds(2).toString()))
                .andExpect(jsonPath("$.closedAt").value(now.plusSeconds(10).toString())).andExpect(jsonPath("$.approvedAt").isEmpty())
                .andExpect(jsonPath("$.executedAt").isEmpty()).andExpect(jsonPath("$.executionState").value("STOPPED")).andExpect(jsonPath("$.requiresAttention").value(false));
        assertEquals(0,jobs.countByRequestId(r.getId()));
    }
    @Test void allSkippedEmptyPlanKeepsBothMilestonesWithoutInventingAStart(){
        var r=submitted(List.of(),"ADMIN",true,null);assertEquals(RequestOutcome.EXECUTED,r.getOutcome());assertEquals(now,r.getApprovedAt());assertEquals(now,r.getExecutedAt());assertNull(r.getExecutionStartedAt());
        at(9);var closed=mediator.send(new CloseRequestCommand(r.getId(),caller("alice")));assertEquals(now,closed.request().getExecutedAt());assertEquals(now.plusSeconds(9),closed.request().getClosedAt());
        assertEquals(COMPLETED,closed.executionProgress().state());assertEquals(0,closed.executionProgress().expectedJobs());assertFalse(closed.executionProgress().requiresAttention());
    }
    @Test void cancellationResponseContainsStoppedJobCountsAndRepeatPreservesTimeAndVersion()throws Exception{
        var r=approved(List.of(JobActions.ACCOUNTING_EXPORT,JobActions.PROVISIONING));at(5);
        mvc.perform(post("/api/v1/requests/"+r.getId()+"/cancel").with(auth("alice"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("CANCELLED")).andExpect(jsonPath("$.cancelledAt").value(now.plusSeconds(5).toString()))
                .andExpect(jsonPath("$.approvedAt").value(now.toString())).andExpect(jsonPath("$.executionState").value("STOPPED"))
                .andExpect(jsonPath("$.executionProgress.jobs.cancelled").value(2)).andExpect(jsonPath("$.executionJobs").isEmpty()).andExpect(jsonPath("$.requiresAttention").value(false));
        long version=request(r.getId()).getVersion();at(20);mediator.send(new CancelRequestCommand(r.getId(),caller("alice")));
        assertEquals(version,request(r.getId()).getVersion());assertEquals(now.plusSeconds(5),request(r.getId()).getCancelledAt());assertEquals(2,summary(r.getId()).jobs().cancelled());
    }
    @Test void businessExpiryAndBlockedReviewAreVisibleWithoutClaimingExecutionSucceeded(){
        var expiring=submitted(List.of(JobActions.ACCOUNTING_EXPORT),"ADMIN",false,1);at(61);review.process(expiring.getId(),true);
        assertEquals(RequestOutcome.EXPIRED,request(expiring.getId()).getOutcome());assertEquals(now.plusSeconds(61),request(expiring.getId()).getExpiredAt());assertEquals(STOPPED,summary(expiring.getId()).state());
        var blocked=submitted(List.of(JobActions.ACCOUNTING_EXPORT),"UNAVAILABLE_"+UUID.randomUUID(),false,null);var p=summary(blocked.getId());
        assertEquals(NOT_PLANNED,p.state());assertTrue(p.requiresAttention());assertEquals(List.of(REVIEW_BLOCKED),p.attentionReasons());assertNull(p.expectedJobs());
    }
    @Test void retryingDeadLetterAndUncertainWorkHaveDistinctCountsAndAttentionClearsAfterSuccess(){
        var retry=approved(List.of(JobActions.ACCOUNTING_EXPORT));var c=claim(rows(retry.getId()).getFirst());finish.finish(c,null,new RuntimeException("retryable"),true);
        assertEquals(RETRYING,summary(retry.getId()).state());assertFalse(summary(retry.getId()).requiresAttention());assertEquals(1,summary(retry.getId()).jobs().retrying());
        at(60);var retryClaim=claim(rows(retry.getId()).getFirst());finish.finish(retryClaim,Map.of("ok",true),null,true);assertEquals(COMPLETED,summary(retry.getId()).state());assertFalse(summary(retry.getId()).requiresAttention());
        var dead=approved(List.of(JobActions.ACCOUNTING_EXPORT));var dc=claim(rows(dead.getId()).getFirst());jdbc.update("UPDATE execution_jobs SET max_retries=1 WHERE id=?",dc.job().getId());finish.finish(dc,null,new RuntimeException("failed"),true);
        assertTrue(summary(dead.getId()).attentionReasons().contains(RETRIES_EXHAUSTED));assertEquals(1,summary(dead.getId()).jobs().deadLetter());
        var uncertain=approved(List.of(JobActions.ACCOUNTING_EXPORT));var uc=claim(rows(uncertain.getId()).getFirst());finish.finish(uc,null,new RuntimeException("uncertain"),false);
        assertEquals(RECONCILIATION_REQUIRED,summary(uncertain.getId()).state());assertEquals(1,summary(uncertain.getId()).jobs().uncertain());assertTrue(summary(uncertain.getId()).attentionReasons().contains(UNCERTAIN_EXECUTION));
        verify(handler,never()).execute(any());verify(handler,never()).reconcile(any());
    }
    @Test void expiredLeaseIsReportedAtReadTimeWithoutMutatingTheRequestOrJob(){
        var r=approved(List.of(JobActions.ACCOUNTING_EXPORT));var c=claim(rows(r.getId()).getFirst());long version=request(r.getId()).getVersion();at(301);
        var p=summary(r.getId());assertEquals(1,p.jobs().expiredLeases());assertTrue(p.attentionReasons().contains(EXECUTION_LEASE_EXPIRED));assertEquals(version,request(r.getId()).getVersion());
        assertEquals(JobStatus.STARTED,rows(r.getId()).getFirst().getStatus());finish.finish(c,null,new RuntimeException("late"),false);
    }
    // Controlled loss fixtures only: neither the application nor the runbook disables database guards.
    private void administrative(Runnable operation){tx().executeWithoutResult(s->{jdbc.execute("SET LOCAL session_replication_role=replica");operation.run();jdbc.execute("SET LOCAL session_replication_role=origin");});}
    @Test void missingJobsAreVisibleAndSuccessfulRecoveryClearsCurrentAttentionDespiteRetainedHistory(){
        var r=approved(List.of(JobActions.ACCOUNTING_EXPORT));var job=rows(r.getId()).getFirst();var plan=plans.findByRequestId(r.getId()).orElseThrow();
        administrative(()->{jdbc.update("DELETE FROM execution_plan_job_bindings WHERE plan_id=?",plan.id());jdbc.update("DELETE FROM execution_jobs WHERE id=?",job.getId());});
        assertEquals(1,summary(r.getId()).jobs().missing());assertEquals(1,summary(r.getId()).jobs().missingBindings());assertTrue(summary(r.getId()).requiresAttention());
        assertEquals(ReconcileExecutionPlan.Outcome.REPAIRED,reconcile.process(r.getId()).outcome());assertEquals(QUEUED,summary(r.getId()).state());assertFalse(summary(r.getId()).requiresAttention());
        assertEquals(job.getId(),rows(r.getId()).getFirst().getId());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM lifecycle_audit_events WHERE request_id=? AND action='RESTORED_FROM_PLAN'",Integer.class,r.getId()));
    }
    @Test void legacyUnknownOutcomeAndUnknownExecutionRemainExplicitRatherThanBecomingEmptySuccess(){
        long unknown=jdbc.queryForObject("INSERT INTO requests(type_id,requester_id,status,payload,execution_start_unknown) SELECT t.id,u.id,'APPROVED','{}'::jsonb,true FROM request_types t,users u WHERE t.code='EXPENSE' AND u.username='alice' RETURNING id",Long.class);
        assertNull(summary(unknown).expectedJobs());assertTrue(summary(unknown).attentionReasons().contains(MISSING_FROZEN_PLAN));assertTrue(summary(unknown).attentionReasons().contains(EXECUTION_HISTORY_UNKNOWN));
        long closed=jdbc.queryForObject("INSERT INTO requests(type_id,requester_id,status,payload) SELECT t.id,u.id,'CLOSED','{}'::jsonb FROM request_types t,users u WHERE t.code='EXPENSE' AND u.username='alice' RETURNING id",Long.class);
        assertNull(detail(closed).outcome());assertNull(detail(closed).closedAt());assertEquals(UNKNOWN,summary(closed).state());assertEquals(List.of(OUTCOME_UNKNOWN),summary(closed).attentionReasons());
    }
    @Test void finalizedRequestsRejectClarificationsAndMetadataCannotBeClearedOrRewritten()throws Exception{
        String outsider="out_"+UUID.randomUUID().toString().replace("-","");
        tx().executeWithoutResult(s->users.save(com.flowops.domain.identity.User.newUser(outsider,"Outsider",null,null,Set.of(),"unused")));
        var r=approved(List.of());mvc.perform(post("/api/v1/requests/"+r.getId()+"/close").with(auth(outsider))).andExpect(status().isForbidden());
        mediator.send(new CloseRequestCommand(r.getId(),caller("alice")));
        for(String column:List.of("approved_at","executed_at","closed_at")){
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE requests SET "+column+"=NULL WHERE id=?",r.getId()));
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE requests SET "+column+"="+column+"+interval '1 second' WHERE id=?",r.getId()));
        }
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE requests SET outcome='REJECTED' WHERE id=?",r.getId()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE requests SET status='APPROVED' WHERE id=?",r.getId()));
        mvc.perform(get("/api/v1/requests/"+r.getId()).with(auth(outsider))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/requests").param("typeCode",r.getType().getCode()).with(auth(outsider)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.content").isEmpty());
        var executed=approved(List.of());
        assertThrows(com.flowops.domain.exception.ConflictException.class,()->clarifications.ask(executed.getId(),"Too late",caller("alice")));
    }
    @Test void unexpectedRowsDoNotInflateExpectedWorkOrHideAnIntegrityProblem(){
        var r=approved(List.of(JobActions.ACCOUNTING_EXPORT));long[] extra=new long[1];
        administrative(()->extra[0]=jdbc.queryForObject("INSERT INTO execution_jobs(request_id,idempotency_key,action,input_payload) VALUES (?,?,'ROGUE','{}'::jsonb) RETURNING id",Long.class,r.getId(),"rogue:"+r.getId()));
        try{
            var p=summary(r.getId());assertEquals(1,p.expectedJobs());assertEquals(2,p.jobs().total());assertEquals(1,p.jobs().unexpected());assertTrue(p.attentionReasons().contains(UNEXPECTED_JOBS));
            var page=mediator.send(new ListRequests(new RequestFilter(null,r.getType().getCode(),null),PageQuery.first(10),caller("alice")));
            assertEquals(1,page.totalElements());assertEquals(1,page.content().size());assertEquals(p,page.content().getFirst().executionProgress());
        }finally{administrative(()->jdbc.update("DELETE FROM execution_jobs WHERE id=?",extra[0]));}
    }
    private void auditFailure(Long id,String condition,Runnable operation){
        String name="outcome_fault_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE FUNCTION "+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'milestone audit fault' USING ERRCODE='23514'; END; $$");
        jdbc.execute("CREATE TRIGGER "+name+" BEFORE INSERT ON lifecycle_audit_events FOR EACH ROW WHEN (NEW.request_id="+id+" AND "+condition+") EXECUTE FUNCTION "+name+"()");
        try{operation.run();}finally{jdbc.execute("DROP TRIGGER "+name+" ON lifecycle_audit_events");jdbc.execute("DROP FUNCTION "+name+"()");}
    }
    @Test void completionAuditFailureRollsBackTheOutcomeTimestampAndJobResultTogether(){
        var r=approved(List.of(JobActions.ACCOUNTING_EXPORT));var c=claim(rows(r.getId()).getFirst());long version=request(r.getId()).getVersion();at(10);
        auditFailure(r.getId(),"NEW.event_type='EXECUTION' AND NEW.action='COMPLETED'",()->assertThrows(org.springframework.dao.DataAccessException.class,()->finish.finish(c,Map.of("ok",true),null,true)));
        var unchanged=request(r.getId());assertEquals(RequestStatus.APPROVED,unchanged.getStatus());assertNull(unchanged.getOutcome());assertNull(unchanged.getExecutedAt());assertEquals(now,unchanged.getApprovedAt());assertEquals(version,unchanged.getVersion());
        assertEquals(JobStatus.STARTED,rows(r.getId()).getFirst().getStatus());assertTrue(finish.finish(c,Map.of("ok",true),null,true));assertEquals(now.plusSeconds(10),request(r.getId()).getExecutedAt());
    }
    @Test void closureAuditFailureLeavesTheBusinessOutcomeAndMilestonesIntact(){
        var r=approved(List.of());long version=r.getVersion();at(10);
        auditFailure(r.getId(),"NEW.event_type='STATUS' AND NEW.after_data->>'status'='CLOSED'",()->assertThrows(org.springframework.dao.DataAccessException.class,()->mediator.send(new CloseRequestCommand(r.getId(),caller("alice")))));
        var unchanged=request(r.getId());assertEquals(RequestStatus.EXECUTED,unchanged.getStatus());assertEquals(RequestOutcome.EXECUTED,unchanged.getOutcome());assertEquals(now,unchanged.getExecutedAt());assertNull(unchanged.getClosedAt());assertEquals(version,unchanged.getVersion());
        mediator.send(new CloseRequestCommand(r.getId(),caller("alice")));assertEquals(now,request(r.getId()).getExecutedAt());assertEquals(now.plusSeconds(10),request(r.getId()).getClosedAt());
    }
    @Test void detailAndProgressUseOneReadSnapshotWhenAFinishCommitsBetweenTheirQueries()throws Exception{
        var r=approved(List.of(JobActions.ACCOUNTING_EXPORT));var c=claim(rows(r.getId()).getFirst());var once=new AtomicBoolean();
        try(var pool=Executors.newSingleThreadExecutor()){
            doAnswer(invocation->{
                Collection<Long> ids=invocation.getArgument(0);
                if(ids.contains(r.getId()) && once.compareAndSet(false,true))assertTrue(pool.submit(()->finish.finish(c,Map.of("ok",true),null,true)).get(10,TimeUnit.SECONDS));
                return invocation.callRealMethod();
            }).when(progress).findByRequestIds(anyCollection());
            var old=detail(r.getId());assertEquals("APPROVED",old.status());assertEquals(RUNNING,old.executionState());assertNull(old.executedAt());
            var current=detail(r.getId());assertEquals("EXECUTED",current.status());assertEquals(COMPLETED,current.executionState());assertEquals(now,current.executedAt());
        }
    }
}
