package com.flowops;

import com.flowops.application.identity.UserService;
import com.flowops.application.organization.RoleService;
import com.flowops.application.request.lifecycle.SubmitRequest;
import com.flowops.application.request.port.EligibilityReconciliationQueue;
import com.flowops.application.request.recovery.*;
import com.flowops.application.request.sla.ReconcileReviewRequest;
import com.flowops.domain.identity.*;
import com.flowops.domain.identity.port.*;
import com.flowops.domain.organization.Department;
import com.flowops.domain.organization.port.DepartmentRepository;
import com.flowops.domain.request.*;
import com.flowops.domain.request.port.*;
import com.flowops.domain.workflow.*;
import com.flowops.domain.workflow.port.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@TestPropertySource(properties="flowops.recovery.team-role=IDENTITY_TEST_OPERATIONS")
class IdentityRecoveryReconciliationTest extends AbstractIntegrationTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.flowops.application.request.port.IdentityReconciliationWakeup wakeup;
    @org.junit.jupiter.api.BeforeEach void wakeup() {
        // Production uses an async worker; run the same bounded drain synchronously for deterministic assertions.
        for(int i=0;i<30 && !queue.findReady(500).isEmpty();i++) drain.drain();
        org.mockito.Mockito.doAnswer(call->{drain.drain();return null;}).when(wakeup).request();
    }

    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PermissionRepository permissions;
    @Autowired DepartmentRepository departments;
    @Autowired WorkflowRepository workflows;
    @Autowired WorkflowStepRepository definitions;
    @Autowired RequestTypeRepository types;
    @Autowired RequestRepository requests;
    @Autowired StepInstanceRepository steps;
    @Autowired SubmitRequest submit;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired ReconcileReviewRequest processor;
    @Autowired EligibilityReconciliationQueue queue;
    @Autowired DrainIdentityReconciliation drain;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }
    private CallerIdentity admin() { var u=users.findByUsername("admin").orElseThrow();return new CallerIdentity(u.getId(),u.getUsername()); }
    private record Fixture(Long request,Long reviewer,Role role,Long firstOwner,Long secondOwner) {}
    private Permission permission(String code) { return permissions.findAll().stream().filter(p->p.getCode().equals(code)&&p.getScopeType()==null).findFirst().orElseThrow(); }
    private Role operators() {
        return roles.findByCode("IDENTITY_TEST_OPERATIONS").orElseGet(()->{
            var grants=new HashSet<Permission>();
            for(String code:List.of("request:recover","request:blocked:read")) {
                String scoped=code+":identity_owner_dept";
                var permission=permissions.findByCode(scoped).orElseGet(()->{
                    Long id=jdbc.queryForObject("INSERT INTO permissions(code,name,scope_type) VALUES (?, 'Department recovery', 'DEPARTMENT') RETURNING id",Long.class,scoped);
                    return permissions.findById(id).orElseThrow();
                });grants.add(permission);
            }
            var role=new Role("IDENTITY_TEST_OPERATIONS","Test recovery operators",null);role.setPermissions(grants);return roles.save(role);
        });
    }
    private Fixture fixture() {
        return tx().execute(s->{
            String key=("IR_"+UUID.randomUUID().toString().substring(0,8)).toUpperCase(java.util.Locale.ROOT);
            jdbc.update("INSERT INTO departments(code,name) VALUES (?, ?)",key,"Recovery test");
            var department=departments.findByCode(key).orElseThrow();
            var operator=operators();
            var first=users.save(User.newUser(key.toLowerCase()+"_op1","Operator one",null,department,Set.of(operator),"unused"));
            var second=users.save(User.newUser(key.toLowerCase()+"_op2","Operator two",null,department,Set.of(operator),"unused"));
            Long grantId=jdbc.queryForObject("INSERT INTO permissions(code,name) VALUES (?, 'Review authority') RETURNING id",Long.class,"request:approve:"+key);
            var role=new Role(key,"Reviewer",null);role.setPermissions(Set.of(permissions.findById(grantId).orElseThrow()));roles.save(role);
            var reviewer=users.save(User.newUser(key.toLowerCase()+"_r","Reviewer",null,department,Set.of(role),"unused"));
            var workflow=workflows.save(new Workflow(key,"Identity reconciliation",null));
            var definition=new WorkflowStep(workflow,1,"REVIEW","Review",key);definition.setExpiryMinutes(60);definitions.save(definition);
            var type=new RequestType(key,"Identity reconciliation",null,department);type.setDefaultWorkflow(workflow);types.save(type);
            var owner=users.findByUsername("alice").orElseThrow();
            var request=requests.save(Request.newDraft(type,owner,Map.of(),null));
            submit.execute(request.getId(),new CallerIdentity(owner.getId(),owner.getUsername()));
            return new Fixture(request.getId(),reviewer.getId(),role,first.getId(),second.getId());
        });
    }
    private StepInstance step(Fixture f) { return tx().execute(s->steps.findByRequestIdOrderById(f.request()).getFirst()); }
    private void active(Long id,boolean active) { userService.update(id,null,null,null,null,null,active,admin()); }
    private int queued(Fixture f) { return jdbc.queryForObject("SELECT count(*) FROM eligibility_reconciliation_queue WHERE request_id=?",Integer.class,f.request()); }
    private int facts(Fixture f,String action) { return jdbc.queryForObject("SELECT count(*) FROM lifecycle_audit_events WHERE request_id=? AND action=?",Integer.class,f.request(),action); }

    @Test void permissionChangesBlockAndRecoverImmediatelyAfterCommit() {
        var f=fixture();
        roleService.update(f.role().getId(),new RoleSpec(null,null,null,List.of()),admin());
        assertEquals(StepState.BLOCKED,step(f).getState());
        assertEquals(f.firstOwner(),step(f).getRecoveryOwnerId());assertEquals(0,queued(f));
        roleService.update(f.role().getId(),new RoleSpec(null,null,null,List.of("request:approve:"+f.role().getCode())),admin());
        assertEquals(StepState.PENDING,step(f).getState());
        assertEquals(1,facts(f,"RECOVERED"));
    }
    @Test void membershipAndAccountChangesTriggerTheSameReconciliation() {
        var f=fixture();
        userService.update(f.reviewer(),null,null,null,List.of("MEMBER"),null,admin());
        assertEquals(StepState.BLOCKED,step(f).getState());
        userService.update(f.reviewer(),null,null,null,List.of(f.role().getCode()),null,admin());
        assertEquals(StepState.PENDING,step(f).getState());
        active(f.reviewer(),false); assertEquals(StepState.BLOCKED,step(f).getState());
        active(f.reviewer(),true); assertEquals(StepState.PENDING,step(f).getState());
    }
    @Test void identityRollbackPublishesNeitherHintsNorWorkflowChanges() {
        var f=fixture();
        tx().executeWithoutResult(s->{ active(f.reviewer(),false);assertTrue(queued(f)>0);s.setRollbackOnly(); });
        assertEquals(0,queued(f));assertTrue(users.findById(f.reviewer()).orElseThrow().isActive());
        assertEquals(StepState.PENDING,step(f).getState());assertEquals(0,facts(f,"BLOCKED"));
    }
    @Test void invalidOwnerIsReassignedWithoutChangingEpisodeOrDeadlines() {
        var f=fixture();active(f.reviewer(),false);
        var before=step(f);
        active(f.firstOwner(),false);
        var after=step(f);
        assertEquals(f.secondOwner(),after.getRecoveryOwnerId());
        assertEquals(before.getBlockedAt(),after.getBlockedAt());assertEquals(before.getBlockEpisode(),after.getBlockEpisode());
        assertEquals(before.getExpiresAt(),after.getExpiresAt());assertEquals(before.getRequiredApprovals(),after.getRequiredApprovals());
        assertEquals(1,after.getRecoveryOwnerRevision());assertEquals(1,facts(f,"OWNER_REASSIGNED"));
        processor.process(f.request(),true);assertEquals(1,facts(f,"OWNER_REASSIGNED"));
        active(f.firstOwner(),true);assertEquals(f.secondOwner(),step(f).getRecoveryOwnerId());
    }
    @Test void scopedOwnerWhoChangesDepartmentLosesResponsibility() {
        var f=fixture();active(f.reviewer(),false);
        var owner=users.findById(f.firstOwner()).orElseThrow();
        userService.update(owner.getId(),null,null,"IT",null,null,admin());
        assertEquals(f.secondOwner(),step(f).getRecoveryOwnerId());assertEquals(1,facts(f,"OWNER_REASSIGNED"));
    }
    @Test void revokedOwnerMembershipTransfersToAuthorizedFallback() {
        var f=fixture();active(f.reviewer(),false);active(f.secondOwner(),false);
        userService.update(f.firstOwner(),null,null,null,List.of("MEMBER"),null,admin());
        assertEquals(admin().id(),step(f).getRecoveryOwnerId());assertFalse(step(f).isRecoveryOwnerUnavailable());
    }
    @Test void newerSignalCannotBeAcknowledgedByAnOlderTicket() {
        var f=fixture();tx().executeWithoutResult(s->queue.userChanged(f.reviewer()));
        var older=queue.findReady(500).stream().filter(t->t.requestId().equals(f.request())).findFirst().orElseThrow();
        tx().executeWithoutResult(s->queue.userChanged(f.reviewer()));
        tx().executeWithoutResult(s->queue.acknowledge(older));assertEquals(1,queued(f));
        drain.drain();assertEquals(0,queued(f));
    }
    @Test void busyRequestKeepsDurableHintUntilARetry() throws Exception {
        var f=fixture();
        try(var pool=Executors.newSingleThreadExecutor()) {
            tx().executeWithoutResult(s->{
                requests.findByIdForUpdate(f.request()).orElseThrow();
                try { pool.submit(()->active(f.reviewer(),false)).get(5,TimeUnit.SECONDS); }
                catch(Exception e) { throw new AssertionError(e); }
                assertEquals(StepState.PENDING,step(f).getState());assertEquals(1,queued(f));
            });
        }
        jdbc.update("UPDATE eligibility_reconciliation_queue SET available_at=clock_timestamp() WHERE request_id=?",f.request());
        drain.drain();assertEquals(StepState.BLOCKED,step(f).getState());assertEquals(0,queued(f));
    }

    @Test void totalOperatorOutageIsVisibleAndRepairedWithoutRepeatedAlerts() {
        var f=fixture();active(f.reviewer(),false);active(f.firstOwner(),false);active(f.secondOwner(),false);
        assertEquals(admin().id(),step(f).getRecoveryOwnerId());
        Long fallback=admin().id();
        var before=step(f);
        try {
            tx().executeWithoutResult(s->{var u=users.findById(fallback).orElseThrow();u.setActive(false);users.save(u);});
            processor.process(f.request(),true);
            var unavailable=step(f);
            assertNull(unavailable.getRecoveryOwnerId());assertTrue(unavailable.isRecoveryOwnerUnavailable());
            assertEquals(before.getBlockEpisode(),unavailable.getBlockEpisode());assertEquals(before.getExpiresAt(),unavailable.getExpiresAt());
            assertEquals(1,facts(f,"OWNER_UNAVAILABLE"));
            processor.process(f.request(),true);assertEquals(1,facts(f,"OWNER_UNAVAILABLE"));
            var newlyUnreachable=fixture();
            tx().executeWithoutResult(s->{
                for(Long id:List.of(newlyUnreachable.reviewer(),newlyUnreachable.firstOwner(),newlyUnreachable.secondOwner())) {
                    var user=users.findById(id).orElseThrow();user.setActive(false);users.save(user);
                }
            });
            processor.process(newlyUnreachable.request(),true);
            assertEquals(StepState.BLOCKED,step(newlyUnreachable).getState());
            assertNull(step(newlyUnreachable).getRecoveryOwnerId());assertTrue(step(newlyUnreachable).isRecoveryOwnerUnavailable());
            assertEquals(1,facts(newlyUnreachable,"BLOCKED"));
        } finally {
            tx().executeWithoutResult(s->{var u=users.findById(fallback).orElseThrow();u.setActive(true);users.save(u);});
        }
        processor.process(f.request(),true);
        assertEquals(fallback,step(f).getRecoveryOwnerId());assertFalse(step(f).isRecoveryOwnerUnavailable());
    }

    @Test void ownerAuditFailureRollsBackReassignmentAndLeavesHintForRetry() {
        var f=fixture();active(f.reviewer(),false);
        long version=tx().execute(s->requests.findById(f.request()).orElseThrow().getVersion());
        String fault="owner_fault_"+UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION "+fault+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'owner audit failed' USING ERRCODE='23514'; END; $$");
        try {
            jdbc.execute("CREATE TRIGGER "+fault+" BEFORE INSERT ON lifecycle_audit_events FOR EACH ROW WHEN (NEW.request_id="+f.request()+" AND NEW.action='OWNER_REASSIGNED') EXECUTE FUNCTION "+fault+"()");
            active(f.firstOwner(),false); // Identity commits even though its after-commit workflow check fails.
            assertFalse(users.findById(f.firstOwner()).orElseThrow().isActive());
            assertEquals(f.firstOwner(),step(f).getRecoveryOwnerId());assertEquals(0,step(f).getRecoveryOwnerRevision());
            Long persistedVersion=tx().execute(s->requests.findById(f.request()).orElseThrow().getVersion());
            assertEquals(version,persistedVersion.longValue());
            assertEquals(1,queued(f));assertEquals(0,facts(f,"OWNER_REASSIGNED"));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS "+fault+" ON lifecycle_audit_events");jdbc.execute("DROP FUNCTION "+fault+"()");
        }
        jdbc.update("UPDATE eligibility_reconciliation_queue SET available_at=clock_timestamp() WHERE request_id=?",f.request());
        drain.drain();assertEquals(f.secondOwner(),step(f).getRecoveryOwnerId());assertEquals(0,queued(f));
    }
}
