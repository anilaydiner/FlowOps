package com.flowops;

import com.flowops.application.mediator.Mediator;
import com.flowops.application.request.ApprovalEligibilityService;
import com.flowops.application.request.command.RecoverRequestCommand;
import com.flowops.application.request.lifecycle.*;
import com.flowops.application.request.port.BlockedRequestQueries;
import com.flowops.application.request.recovery.*;
import com.flowops.domain.exception.*;
import com.flowops.domain.identity.*;
import com.flowops.domain.identity.port.*;
import com.flowops.domain.organization.port.DepartmentRepository;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.request.*;
import com.flowops.domain.request.port.*;
import com.flowops.domain.workflow.*;
import com.flowops.domain.workflow.port.*;
import com.flowops.security.UserPrincipal;
import com.flowops.worker.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class BlockedRequestRecoveryTest extends AbstractIntegrationTest {
    @MockitoBean JobWorker backgroundJobs;
    @MockitoBean OutboxDispatcher backgroundOutbox;
    @MockitoBean SlaScanner backgroundSla;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PermissionRepository permissions;
    @Autowired DepartmentRepository departments;
    @Autowired WorkflowRepository workflows;
    @Autowired WorkflowStepRepository definitions;
    @Autowired RequestTypeRepository types;
    @Autowired RequestRepository requests;
    @Autowired StepInstanceRepository steps;
    @Autowired StepParticipantRepository participants;
    @Autowired DecisionRepository decisions;
    @Autowired SubmitRequest submit;
    @Autowired DecideStep decide;
    @Autowired CancelRequest cancel;
    @Autowired RecoverBlockedRequest recovery;
    @Autowired BlockedRequests blocked;
    @Autowired RecoveryOperations operations;
    @Autowired ApprovalEligibilityService eligibility;
    @Autowired Mediator mediator;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;

    private record Fixture(Long requestId, Long stepId, Role role, List<Long> people) {}
    private CallerIdentity caller(String name) {
        var user = users.findByUsername(name).orElseThrow(); return new CallerIdentity(user.getId(), user.getUsername());
    }
    private CallerIdentity caller(Long id) {
        var user = users.findById(id).orElseThrow(); return new CallerIdentity(id, user.getUsername());
    }
    private RequestPostProcessor authenticated(CallerIdentity caller) {
        return authentication(new TestingAuthenticationToken(new UserPrincipal(caller.id(), caller.username(), Set.of(), null), null, "ROLE_TEST"));
    }
    private Permission globalPermission(String code) {
        return permissions.findAll().stream().filter(p -> p.getCode().equals(code) && p.getScopeType() == null).findFirst().orElseThrow();
    }
    private Long addPerson(Role role) {
        return users.save(User.newUser("br_" + UUID.randomUUID().toString().substring(0, 12), "Recovery reviewer", null,
                departments.findByCode("FINANCE").orElseThrow(), Set.of(role), "unused")).getId();
    }
    private Fixture fixture(CompletionRule rule, int threshold, int count) {
        String key = "BR_" + UUID.randomUUID().toString().substring(0, 8);
        Role role = new Role(key, "Recovery reviewers", null);
        role.setPermissions(Set.of(globalPermission("request:approve"),
                globalPermission("request:reject"), permissions.findByCode("request:queue").orElseThrow()));
        role = roles.save(role);
        List<Long> people = new ArrayList<>();
        for (int i=0; i<count; i++) people.add(addPerson(role));
        var workflow = workflows.save(new Workflow(key, "Blocked recovery", null));
        var definition = new WorkflowStep(workflow, 1, "REVIEW", "Review", role.getCode());
        definition.configure(rule, threshold, Map.of(), 60, null, false);
        definitions.save(definition);
        var type = new RequestType(key, "Recovery", null, departments.findByCode("FINANCE").orElseThrow());
        type.setDefaultWorkflow(workflow); types.save(type);
        var request = requests.save(Request.newDraft(type, users.findByUsername("alice").orElseThrow(), Map.of(), null));
        submit.execute(request.getId(), caller("alice"));
        var step = steps.findByRequestIdOrderById(request.getId()).getFirst();
        return new Fixture(request.getId(), step.getId(), role, List.copyOf(people));
    }
    private StepInstance step(Fixture f) { return steps.findByRequestIdOrderById(f.requestId()).getFirst(); }
    private Request request(Fixture f) { return requests.findById(f.requestId()).orElseThrow(); }
    private void active(Long id, boolean value) { var user=users.findById(id).orElseThrow(); user.setActive(value); users.save(user); }
    private void approve(Fixture f, Long user) { decide.execute(f.requestId(), DecisionAction.APPROVE, "Reviewed", caller(user)); }
    private void recheck(Fixture f) { recovery.recheck(f.requestId(), f.stepId(), request(f).getVersion(), "Eligibility changed", caller("admin")); }
    private void recover(Fixture f, boolean refresh, List<RecoveryAssignment> assignments) {
        recovery.recover(f.requestId(), f.stepId(), request(f).getVersion(), "Staff coverage restored", refresh, assignments, caller("admin"));
    }
    private int count(String table, Fixture f) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE step_instance_id=?", Integer.class, f.stepId());
    }
    private int alerts(Fixture f) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE idempotency_key LIKE ?", Integer.class,
                "req:" + f.requestId() + ":%BLOCKED:%");
    }

    @Test @Transactional
    void noCandidateBlockHasOwnerReasonTimestampAndOneAlertSetPerEpisode() {
        var f=fixture(CompletionRule.ANY_OF,1,0);
        var step=step(f);
        assertEquals(RequestStatus.IN_REVIEW,request(f).getStatus());
        assertEquals(StepState.BLOCKED,step.getState());
        assertEquals(BlockReason.NO_ELIGIBLE_APPROVER,step.getBlockReasonCode());
        assertNotNull(step.getBlockedAt()); assertNotNull(step.getRecoveryOwnerId());
        assertEquals("WORKFLOW_OPERATIONS",step.getRecoveryTeam());
        assertEquals(1,step.getBlockEpisode()); assertFalse(step.isTerminal());
        assertEquals(4,alerts(f)); // requester + operator, FEED + EMAIL
        Long version=request(f).getVersion();
        recheck(f); recheck(f);
        assertEquals(version,request(f).getVersion());
        assertEquals(step.getBlockedAt(),step(f).getBlockedAt());
        assertEquals(4,alerts(f));
    }

    @Test @Transactional
    void numericPoolRefreshRecoversAnInsufficientRosterWithoutChangingThreshold() {
        var f=fixture(CompletionRule.K_OF_N,2,1);
        assertEquals(BlockReason.INSUFFICIENT_QUORUM,step(f).getBlockReasonCode());
        Long second=addPerson(f.role());
        recover(f,true,List.of());
        assertEquals(StepState.PENDING,step(f).getState());
        assertEquals(RequestStatus.IN_REVIEW,request(f).getStatus());
        assertEquals(2,step(f).getRequiredApprovals());
        assertEquals(1,step(f).getRosterSize()); // original activation evidence
        assertEquals(2,participants.findByStepInstanceId(f.stepId()).size());
        assertEquals(1,count("step_recoveries",f));
        assertTrue(participants.findByStepInstanceId(f.stepId()).stream().anyMatch(p->p.recoveryId()!=null));
        approve(f,f.people().getFirst()); approve(f,second);
        assertEquals(RequestStatus.EXECUTED,request(f).getStatus());
    }

    @Test @Transactional
    void restoredAllOfParticipantsKeepVotesStepIdThresholdAndDeadline() {
        var f=fixture(CompletionRule.ALL_OF,1,3);
        Instant activated=step(f).getCreatedAt(), deadline=step(f).slaDeadline();
        approve(f,f.people().getFirst());
        active(f.people().get(1),false); active(f.people().get(2),false);
        recheck(f);
        assertEquals(BlockReason.PARTICIPANT_UNAVAILABLE,step(f).getBlockReasonCode());
        assertEquals(1,decisions.countDistinctApprovers(f.stepId()));
        active(f.people().get(1),true); active(f.people().get(2),true);
        recover(f,false,List.of());
        assertEquals(f.stepId(),step(f).getId());
        assertEquals(activated,step(f).getCreatedAt()); assertEquals(deadline,step(f).slaDeadline());
        assertEquals(3,step(f).getRequiredApprovals()); assertEquals(1,decisions.countDistinctApprovers(f.stepId()));
        approve(f,f.people().get(1)); approve(f,f.people().get(2));
        assertEquals(RequestStatus.EXECUTED,request(f).getStatus());
    }

    @Test @Transactional
    void validHistoricalVoteSurvivesRevocationAndDoesNotCountAgainAsRemaining() {
        var f=fixture(CompletionRule.K_OF_N,2,2);
        approve(f,f.people().getFirst()); active(f.people().getFirst(),false);
        recheck(f);
        assertEquals(StepState.PENDING,step(f).getState());
        assertEquals(1,eligibility.reachability(request(f),step(f)).votes());
        assertEquals(List.of(f.people().get(1)),eligibility.reachability(request(f),step(f)).remaining());
    }

    @Test @Transactional
    void allOfReplacementTransfersOneUnvotedSlotAndKeepsAppendOnlyHistory() {
        var f=fixture(CompletionRule.ALL_OF,1,2);
        approve(f,f.people().getFirst()); active(f.people().get(1),false); recheck(f);
        var previous=participants.findByStepInstanceId(f.stepId()).stream().filter(p->p.userId().equals(f.people().get(1))).findFirst().orElseThrow();
        recover(f,false,List.of(new RecoveryAssignment(previous.id(),caller("admin").id())));
        var history=participants.findByStepInstanceId(f.stepId());
        assertEquals(3,history.size());
        var replacement=history.stream().filter(p->p.recoveryId()!=null).findFirst().orElseThrow();
        assertEquals(previous.id(),replacement.predecessorId()); assertEquals(previous.slotId(),replacement.slotId());
        assertEquals(2,step(f).getRequiredApprovals());
        approve(f,caller("admin").id());
        assertEquals(RequestStatus.EXECUTED,request(f).getStatus());
    }

    @Test @Transactional
    void insufficientRecoveryRollsBackAssignmentsHistoryAndVersion() {
        var f=fixture(CompletionRule.K_OF_N,3,1);
        addPerson(f.role());
        Long version=request(f).getVersion();
        assertThrows(ConflictException.class,()->recover(f,true,List.of()));
        assertEquals(StepState.BLOCKED,step(f).getState());
        assertEquals(version,request(f).getVersion());
        // The surrounding test transaction is rollback-only; persisted rollback is checked separately below.
    }

    @Test @Transactional
    void recoveryCannotReplaceAnAlreadyVotedSlotOrReuseAPerson() {
        var f=fixture(CompletionRule.ALL_OF,1,2);
        approve(f,f.people().getFirst()); active(f.people().get(1),false); recheck(f);
        var voted=participants.findByStepInstanceId(f.stepId()).stream().filter(p->p.userId().equals(f.people().getFirst())).findFirst().orElseThrow();
        assertThrows(ConflictException.class,()->recover(f,false,List.of(new RecoveryAssignment(voted.id(),caller("admin").id()))));
        assertEquals(1,decisions.countDistinctApprovers(f.stepId()));
    }

    @Test @Transactional
    void emptyAllOfNeedsAnExplicitEligibleSlotAndStillRequiresAVote() {
        var f=fixture(CompletionRule.ALL_OF,1,0);
        Long reviewer=addPerson(f.role());
        recover(f,false,List.of(new RecoveryAssignment(null,reviewer)));
        assertEquals(1,step(f).getRequiredApprovals()); assertEquals(0,step(f).getRosterSize());
        assertEquals(RequestStatus.IN_REVIEW,request(f).getStatus());
        approve(f,reviewer); assertEquals(RequestStatus.EXECUTED,request(f).getStatus());
    }

    @Test @Transactional
    void stoppedRequestsRejectRecoveryAndRejectionStopsTheStep() {
        var f=fixture(CompletionRule.ANY_OF,1,0);
        cancel.execute(f.requestId(),caller("alice"));
        assertEquals(StepState.CANCELLED,step(f).getState());
        assertThrows(ConflictException.class,()->recover(f,true,List.of()));
        var rejected=fixture(CompletionRule.ANY_OF,1,1);
        decide.execute(rejected.requestId(),DecisionAction.REJECT,"Declined",caller(rejected.people().getFirst()));
        assertEquals(StepState.REJECTED,step(rejected).getState());
    }

    @Test @Transactional
    void operatorPermissionAndExpectedVersionAreMandatory() throws Exception {
        var f=fixture(CompletionRule.ANY_OF,1,0);
        String body="{\"stepInstanceId\":"+f.stepId()+",\"expectedVersion\":"+request(f).getVersion()+",\"reason\":\"Restore coverage\"}";
        mvc.perform(post("/api/v1/requests/"+f.requestId()+"/recover").with(authenticated(caller("alice")))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/requests/"+f.requestId()+"/recover").with(authenticated(caller("admin")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/requests/"+f.requestId()+"/recover").with(authenticated(caller("admin")))
                .contentType(MediaType.APPLICATION_JSON).content(body.replace("\"expectedVersion\":"+request(f).getVersion(),"\"expectedVersion\":999999")))
                .andExpect(status().isConflict());
    }

    @Test @Transactional
    void scopedBlockedQueueFiltersBeforeCountingAndPaginationAndGrantsDetailContext() throws Exception {
        var a=fixture(CompletionRule.ANY_OF,1,0); var b=fixture(CompletionRule.ANY_OF,1,0);
        Long permissionId=jdbc.queryForObject("INSERT INTO permissions(code,name,scope_type,scope_value) VALUES (?, 'Scoped operator', 'REQUEST_TYPE', ?) RETURNING id",
                Long.class,"request:blocked:read:"+a.role().getCode(),request(a).getType().getCode());
        Role operator=new Role("OP_"+UUID.randomUUID().toString().substring(0,8),"Scoped operator",null);
        operator.setPermissions(Set.of(permissions.findById(permissionId).orElseThrow())); operator=roles.save(operator);
        var caller=caller(addPerson(operator));
        var filter=new BlockedRequestQueries.Filter(BlockReason.NO_ELIGIBLE_APPROVER,Instant.now().plusSeconds(1),step(a).getRecoveryOwnerId(),"WORKFLOW_OPERATIONS");
        var first=blocked.list(PageQuery.first(1),filter,caller);
        assertEquals(1,first.totalElements()); assertEquals(a.requestId(),first.content().getFirst().request().id());
        var beyond=blocked.list(PageQuery.of(1,1,"id",false),filter,caller);
        assertEquals(1,beyond.totalElements()); assertTrue(beyond.content().isEmpty());
        mvc.perform(get("/api/v1/requests/blocked").with(authenticated(caller)).param("size","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/requests/"+a.requestId()).with(authenticated(caller))).andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].blockReasonCode").value("NO_ELIGIBLE_APPROVER"));
        mvc.perform(get("/api/v1/requests/"+b.requestId()).with(authenticated(caller))).andExpect(status().isForbidden());
    }

    @Test
    void racingRecoveriesCommitOnceAndCancellationAlwaysStopsTheActiveStep() throws Exception {
        var tx=new TransactionTemplate(transactions);
        Fixture f=tx.execute(s->fixture(CompletionRule.ANY_OF,1,0));
        tx.executeWithoutResult(s->addPerson(f.role()));
        long version=tx.execute(s->request(f).getVersion());
        var admin=caller("admin");
        CyclicBarrier start=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<2;i++) results.add(pool.submit(()->{
                start.await(10,TimeUnit.SECONDS);
                try { recovery.recover(f.requestId(),f.stepId(),version,"Concurrent recovery",true,List.of(),admin); return true; }
                catch(ConflictException expected) { return false; }
            }));
            int successes=0; for(var result:results) if(result.get(15,TimeUnit.SECONDS)) successes++;
            assertEquals(1,successes);
        }
        tx.executeWithoutResult(s->{
            assertEquals(1,count("step_recoveries",f));
            cancel.execute(f.requestId(),caller("alice"));
            assertEquals(RequestStatus.CANCELLED,request(f).getStatus());
            assertEquals(StepState.CANCELLED,step(f).getState());
        });
    }

    @Test
    void failedRecoveryLeavesNoCommittedAssignmentsOrRecoveryAuthorization() {
        var tx=new TransactionTemplate(transactions);
        Fixture f=tx.execute(s->fixture(CompletionRule.K_OF_N,3,1));
        tx.executeWithoutResult(s->addPerson(f.role()));
        assertThrows(ConflictException.class,()->recover(f,true,List.of()));
        tx.executeWithoutResult(s->{
            assertEquals(0,count("step_recoveries",f));
            assertEquals(1,participants.findByStepInstanceId(f.stepId()).size());
            assertEquals(StepState.BLOCKED,step(f).getState());
        });
    }

    @Test
    void auditFailureRollsBackTheEntireRecovery() {
        var tx=new TransactionTemplate(transactions);
        Fixture f=tx.execute(s->fixture(CompletionRule.ANY_OF,1,0));
        tx.executeWithoutResult(s->addPerson(f.role()));
        long version=tx.execute(s->request(f).getVersion());
        String fault="recovery_fault_"+UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION "+fault+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                +"RAISE EXCEPTION 'injected recovery audit failure' USING ERRCODE='23514'; END; $$");
        try {
            jdbc.execute("CREATE TRIGGER "+fault+" BEFORE INSERT ON lifecycle_audit_events FOR EACH ROW WHEN "
                    +"(NEW.action='RECOVERED' AND NEW.request_id="+f.requestId()+") EXECUTE FUNCTION "+fault+"()");
            assertThrows(org.springframework.dao.DataAccessException.class,()->recover(f,true,List.of()));
            tx.executeWithoutResult(s->{
                assertEquals(StepState.BLOCKED,step(f).getState());
                assertEquals(version,request(f).getVersion());
                assertEquals(0,count("step_recoveries",f));
                assertTrue(participants.findByStepInstanceId(f.stepId()).isEmpty());
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM outbox WHERE idempotency_key LIKE ?",Integer.class,
                        "req:"+f.requestId()+":%RECOVERED:%"));
            });
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS "+fault+" ON lifecycle_audit_events");
            jdbc.execute("DROP FUNCTION "+fault+"()");
        }
    }

    @Test
    void recoveryWaitingBehindCancellationCannotReopenTheRequest() throws Exception {
        var tx=new TransactionTemplate(transactions);
        Fixture f=tx.execute(s->fixture(CompletionRule.ANY_OF,1,0));
        tx.executeWithoutResult(s->addPerson(f.role()));
        long version=tx.execute(s->request(f).getVersion());
        var admin=caller("admin");
        var backend=new java.util.concurrent.atomic.AtomicInteger();
        var waiting=new java.util.concurrent.atomic.AtomicReference<Future<?>>();
        try(var pool=Executors.newSingleThreadExecutor()) {
            tx.executeWithoutResult(s->{
                cancel.execute(f.requestId(),caller("alice"));
                waiting.set(pool.submit(()->tx.execute(status->{
                    backend.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                    return recovery.recover(f.requestId(),f.stepId(),version,"Queued recovery",true,List.of(),admin);
                })));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
                boolean observedWait=false;
                while(System.nanoTime()<deadline) {
                    if(backend.get()!=0 && Boolean.TRUE.equals(jdbc.queryForObject(
                            "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock')",Boolean.class,backend.get()))) {
                        observedWait=true; break;
                    }
                    Thread.yield();
                }
                assertTrue(observedWait,"Recovery must actually wait on the request lock");
            });
            var error=assertThrows(ExecutionException.class,()->waiting.get().get(10,TimeUnit.SECONDS));
            assertInstanceOf(ConflictException.class,error.getCause());
        }
        tx.executeWithoutResult(s->{
            assertEquals(RequestStatus.CANCELLED,request(f).getStatus());
            assertEquals(StepState.CANCELLED,step(f).getState());
            assertEquals(0,count("step_recoveries",f));
        });
    }

    @Test @Transactional
    void recoveryEndpointReturnsCurrentMetadataAndAuthorizedRecoveryHistory() throws Exception {
        var f=fixture(CompletionRule.ANY_OF,1,0);
        addPerson(f.role());
        mvc.perform(post("/api/v1/requests/"+f.requestId()+"/recover").with(authenticated(caller("admin")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"stepInstanceId\":"+f.stepId()+",\"expectedVersion\":"
                        +request(f).getVersion()+",\"reason\":\"Restore review coverage\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_REVIEW"))
                .andExpect(jsonPath("$.steps[0].state").value("PENDING"))
                .andExpect(jsonPath("$.steps[0].recoveredAt").isNotEmpty());
        mvc.perform(get("/api/v1/requests/"+f.requestId()+"/recoveries").with(authenticated(caller("admin"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].reason").value("Restore review coverage"));
        mvc.perform(get("/api/v1/requests/"+f.requestId()+"/recoveries").with(authenticated(caller("alice"))))
                .andExpect(status().isForbidden());
        assertEquals(0,decisions.countDistinctApprovers(f.stepId()));
    }

    @Test @Transactional
    void aNewBlockEpisodeGetsNewAlertsWithoutResettingActivationTime() {
        var f=fixture(CompletionRule.ANY_OF,1,0);
        Instant activated=step(f).getCreatedAt();
        Long reviewer=addPerson(f.role());
        recover(f,true,List.of());
        active(reviewer,false);
        recheck(f);
        assertEquals(2,step(f).getBlockEpisode());
        assertEquals(activated,step(f).getCreatedAt());
        assertNull(step(f).getRecoveredAt());
        assertEquals(8,alerts(f));
        recheck(f);
        assertEquals(8,alerts(f));
    }

    @Test @Transactional
    void stepScopedOperatorsCanReadRecoverAndReadOnlyTheirRecoveryHistory() throws Exception {
        var f=fixture(CompletionRule.ANY_OF,1,0);
        Set<Permission> grants=new HashSet<>();
        for(String code:List.of("request:blocked:read","request:recover")) {
            Long id=jdbc.queryForObject("INSERT INTO permissions(code,name,scope_type,scope_value) VALUES (?, 'Step operator', 'STEP', 'REVIEW') RETURNING id",
                    Long.class,code+":"+f.role().getCode());
            grants.add(permissions.findById(id).orElseThrow());
        }
        Role operator=new Role("OS_"+UUID.randomUUID().toString().substring(0,8),"Step operator",null);
        operator.setPermissions(grants); operator=roles.save(operator);
        var caller=caller(addPerson(operator));
        mvc.perform(get("/api/v1/requests/"+f.requestId()).with(authenticated(caller))).andExpect(status().isOk());
        addPerson(f.role());
        recovery.recover(f.requestId(),f.stepId(),request(f).getVersion(),"Scoped recovery",true,List.of(),caller);
        assertEquals(1,blocked.history(f.requestId(),caller).size());
        assertEquals(StepState.PENDING,step(f).getState());
    }
}
