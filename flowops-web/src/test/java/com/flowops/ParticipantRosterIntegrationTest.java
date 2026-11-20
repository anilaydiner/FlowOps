package com.flowops;

import com.flowops.application.mediator.Mediator;
import com.flowops.application.request.ApprovalEligibilityService;
import com.flowops.application.request.lifecycle.DecideStep;
import com.flowops.application.request.lifecycle.SubmitRequest;
import com.flowops.application.request.port.ApprovalQueueQueries;
import com.flowops.application.request.query.GetRequestParticipants;
import com.flowops.application.request.query.GetRequest;
import com.flowops.application.workflow.WorkflowService;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.ForbiddenException;
import com.flowops.domain.exception.RequestValidationException;
import com.flowops.domain.identity.*;
import com.flowops.domain.identity.port.*;
import com.flowops.domain.organization.port.DepartmentRepository;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.request.*;
import com.flowops.domain.request.port.*;
import com.flowops.domain.workflow.*;
import com.flowops.domain.workflow.port.*;
import com.flowops.worker.JobWorker;
import com.flowops.worker.OutboxDispatcher;
import com.flowops.worker.SlaScanner;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ParticipantRosterIntegrationTest extends AbstractIntegrationTest {

    @MockitoBean
    JobWorker backgroundJobs;

    @MockitoBean
    OutboxDispatcher backgroundOutbox;

    @MockitoBean
    SlaScanner backgroundSla;

    @Autowired
    UserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    PermissionRepository permissions;

    @Autowired
    DepartmentRepository departments;

    @Autowired
    RequestTypeRepository types;

    @Autowired
    WorkflowRepository workflows;

    @Autowired
    WorkflowStepRepository definitions;

    @Autowired
    RequestRepository requests;

    @Autowired
    StepInstanceRepository steps;

    @Autowired
    StepParticipantRepository participants;

    @Autowired
    DecisionRepository decisions;

    @Autowired
    SubmitRequest submit;

    @Autowired
    DecideStep decide;

    @Autowired
    ApprovalEligibilityService eligibility;

    @Autowired
    ApprovalQueueQueries queue;

    @Autowired
    WorkflowService workflowService;

    @Autowired
    Mediator mediator;

    @Autowired
    com.flowops.application.audit.AuditService auditService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    MockMvc mvc;

    @Autowired
    PasswordEncoder encoder;

    private record Fixture(Request request, StepInstance step, Role role, List<User> people) {
    }

    private CallerIdentity caller(User user) {
        return new CallerIdentity(user.getId(), user.getUsername());
    }

    private Permission global(String code) {
        return permissions.findAll()
            .stream()
            .filter(p -> p.getCode().equals(code) && p.getScopeType() == null)
            .findFirst()
            .orElseThrow();
    }

    private User person(Role role) {
        return users.save(User.newUser("roster_" + UUID.randomUUID().toString().substring(0, 8), "Roster member", null,
                departments.findByCode("FINANCE").orElseThrow(), Set.of(role), "unused"));
    }

    private Fixture fixture(CompletionRule rule, int k, int count) {
        String unique = "RO_" + UUID.randomUUID().toString().substring(0, 8);
        Role role = new Role(unique, "Reviewers", null);
        role.setPermissions(Set.of(global("request:approve"), global("request:reject"), global("request:queue")));
        role = roles.save(role);
        List<User> people = new ArrayList<>();
        for (int i = 0; i < count; i++)
            people.add(person(role));
        Workflow workflow = workflows.save(new Workflow(unique, "Roster workflow", null));
        WorkflowStep definition = new WorkflowStep(workflow, 1, "REVIEW", "Original review", role.getCode());
        definition.configure(rule, k, Map.of(), 15, "ADMIN", false);
        definitions.save(definition);
        RequestType type = new RequestType(unique, "Roster request", null,
                departments.findByCode("FINANCE").orElseThrow());
        type.setDefaultWorkflow(workflow);
        types.save(type);
        User owner = users.findByUsername("alice").orElseThrow();
        Request draft = requests.save(Request.newDraft(type, owner, Map.of(), null));
        Request request = submit.execute(draft.getId(), caller(owner));
        StepInstance step = steps.findByRequestIdOrderById(request.getId()).getFirst();
        return new Fixture(request, step, role, people);
    }

    private Request approve(Fixture f, User user) {
        return decide.execute(f.request().getId(), DecisionAction.APPROVE, "approved", caller(user));
    }

    @Test
    @Transactional
    void allOfRequiresEveryFrozenSlotAndLateRoleHoldersCannotJoin() {
        Fixture f = fixture(CompletionRule.ALL_OF, 1, 3);
        assertTrue(f.step().isRosterSnapshotted());
        assertEquals(3, f.step().getRequiredApprovals());
        assertEquals(3, f.step().getRosterSize());
        assertEquals(3, participants.findByStepInstanceId(f.step().getId()).size());
        User late = person(f.role());
        assertThrows(ForbiddenException.class, () -> approve(f, late));
        assertEquals(0, queue.findPage(PageQuery.first(10), eligibility.authority(late.getId(), DecisionAction.APPROVE))
            .totalElements());
        assertEquals(RequestStatus.IN_REVIEW, approve(f, f.people().get(0)).getStatus());
        assertEquals(RequestStatus.IN_REVIEW, approve(f, f.people().get(1)).getStatus());
        assertEquals(RequestStatus.EXECUTED, approve(f, f.people().get(2)).getStatus());
        assertEquals(3L,
                jdbc.queryForObject("SELECT count(DISTINCT slot_id) FROM step_approval_votes WHERE step_instance_id=?",
                        Long.class, f.step().getId()));
    }

    @Test
    @Transactional
    void revocationDoesNotShrinkAllOfAndRestoringAuthorityPreservesOriginalSlot() {
        Fixture f = fixture(CompletionRule.ALL_OF, 1, 2);
        User unavailable = f.people().get(1);
        unavailable.setRoles(Set.of());
        users.save(unavailable);
        assertEquals(RequestStatus.IN_REVIEW, approve(f, f.people().getFirst()).getStatus());
        assertThrows(ForbiddenException.class, () -> approve(f, unavailable));
        assertEquals(2, steps.findByRequestIdOrderById(f.request().getId()).getFirst().getRequiredApprovals());
        assertEquals(2, participants.findByStepInstanceId(f.step().getId()).size());
        unavailable.setRoles(Set.of(f.role()));
        users.save(unavailable);
        assertEquals(RequestStatus.EXECUTED, approve(f, unavailable).getStatus());
    }

    @Test
    @Transactional
    void delegationReplacesOneSlotAndCannotMergeTwoRosterMembers() {
        Fixture f = fixture(CompletionRule.ALL_OF, 1, 2);
        User from = f.people().getFirst();
        assertThrows(ForbiddenException.class, () -> decide.execute(f.request().getId(), DecisionAction.DELEGATE,
                "would collapse slots", caller(from), f.people().get(1).getId()));
        User replacement = users.findByUsername("admin").orElseThrow();
        StepParticipant original = participants.findByStepInstanceId(f.step().getId())
            .stream()
            .filter(p -> p.userId().equals(from.getId()))
            .findFirst()
            .orElseThrow();
        decide.execute(f.request().getId(), DecisionAction.DELEGATE, "handover", caller(from), replacement.getId());
        List<StepParticipant> history = participants.findByStepInstanceId(f.step().getId());
        assertEquals(3, history.size());
        StepParticipant successor = history.stream().filter(p -> p.predecessorId() != null).findFirst().orElseThrow();
        assertEquals(original.id(), successor.predecessorId());
        assertEquals(original.slotId(), successor.slotId());
        assertEquals(replacement.getId(), successor.userId());
        assertNotNull(successor.decisionId());
        assertThrows(ForbiddenException.class, () -> approve(f, from));
        assertEquals(1,
                queue.findPage(PageQuery.first(10), eligibility.authority(replacement.getId(), DecisionAction.APPROVE))
                    .content()
                    .stream()
                    .filter(r -> r.id().equals(f.request().getId()))
                    .count());
        assertEquals(RequestStatus.IN_REVIEW, approve(f, replacement).getStatus());
        assertEquals(RequestStatus.EXECUTED, approve(f, f.people().get(1)).getStatus());
        assertEquals(2, decisions.countDistinctApprovers(f.step().getId()));
    }

    @Test
    @Transactional
    void anyOfAndKOfNUseFrozenPoolsWithoutRequiringEveryone() {
        Fixture any = fixture(CompletionRule.ANY_OF, 1, 3);
        assertEquals(RequestStatus.EXECUTED, approve(any, any.people().getFirst()).getStatus());
        Fixture quorum = fixture(CompletionRule.K_OF_N, 2, 3);
        assertEquals(RequestStatus.IN_REVIEW, approve(quorum, quorum.people().getFirst()).getStatus());
        assertEquals(RequestStatus.EXECUTED, approve(quorum, quorum.people().get(1)).getStatus());
    }

    @Test
    @Transactional
    void emptyAllOfNeverAutoApprovesAndImpossibleQuorumIsBlocked() {
        Fixture empty = fixture(CompletionRule.ALL_OF, 1, 0);
        assertEquals(RequestStatus.IN_REVIEW, empty.request().getStatus());
        assertEquals(StepState.BLOCKED, empty.step().getState());
        assertEquals(0, empty.step().getRosterSize());
        Fixture impossible = fixture(CompletionRule.K_OF_N, 3, 2);
        assertEquals(StepState.BLOCKED, impossible.step().getState());
        assertEquals(3, impossible.step().getRequiredApprovals());
    }

    @Test
    @Transactional
    void policySnapshotsSurviveDefinitionChangesAndUsedWorkflowCannotBeReplaced() {
        Fixture f = fixture(CompletionRule.ALL_OF, 1, 2);
        var original = f.step().getStep();
        var changed = WorkflowStep.restore(new WorkflowStep.Snapshot(original.getId(), original.getWorkflow(),
                original.getPosition(), "CHANGED", original.getName(), "OTHER", original.getCompletionRule(),
                original.getRequiredApprovals(), original.getCondition(), 999, original.getExpiryMinutes(),
                original.getEscalationIntervalMinutes(), original.getMaxEscalationLevels(),
                original.getEscalationRole(), original.isTerminal(), original.getCreatedAt()));
        definitions.save(changed);
        StepInstance restored = steps.findByRequestIdOrderById(f.request().getId()).getFirst();
        assertEquals("REVIEW", restored.getStepCode());
        assertEquals(f.role().getCode(), restored.getRequiredRole());
        assertEquals(15, restored.getSlaMinutes());
        assertEquals(RequestStatus.IN_REVIEW, approve(f, f.people().getFirst()).getStatus());
        User admin = users.findByUsername("admin").orElseThrow();
        assertThrows(ConflictException.class,
                () -> workflowService.update(changed.getWorkflow().getId(), "new", null, true, List.of(new StepSpec(1,
                        "REVIEW", "Review", "OTHER", CompletionRule.ANY_OF, 1, Map.of(), null, null, false)),
                        caller(admin)));
    }

    @Test
    @Transactional
    void numericAllOfConfigurationIsRejectedWithKOfNGuidance() {
        User admin = users.findByUsername("admin").orElseThrow();
        var error = assertThrows(RequestValidationException.class,
                () -> workflowService.create("INVALID_" + UUID.randomUUID().toString().substring(0, 8), "Invalid", null,
                        true, List.of(new StepSpec(1, "REVIEW", "Review", "ADMIN", CompletionRule.ALL_OF, 2, Map.of(),
                                null, null, false)),
                        caller(admin)));
        assertTrue(error.getViolations().get("requiredApprovals").contains("K_OF_N"));
    }

    @Test
    @Transactional
    void rosterHistoryUsesRequestReadAuthorization() {
        Fixture f = fixture(CompletionRule.ALL_OF, 1, 2);
        User owner = f.request().getRequester();
        assertEquals(2, mediator.send(new GetRequestParticipants(f.request().getId(), caller(owner))).size());
        // Current participation grants this request's context, without a broad read
        // grant.
        assertEquals(2,
                mediator.send(new GetRequestParticipants(f.request().getId(), caller(f.people().getFirst()))).size());
    }

    @Test
    @Transactional
    void currentParticipantsCanReadAfterTheirVoteButNotAfterStepCompletion() {
        Fixture f = fixture(CompletionRule.ALL_OF, 1, 2);
        User first = f.people().getFirst();
        assertEquals(f.request().getId(),
                mediator.send(new GetRequest(f.request().getId(), caller(first))).request().id());
        approve(f, first);
        assertEquals(f.request().getId(),
                mediator.send(new GetRequest(f.request().getId(), caller(first))).request().id());
        approve(f, f.people().get(1));
        assertThrows(ForbiddenException.class, () -> mediator.send(new GetRequest(f.request().getId(), caller(first))));
        assertEquals(f.request().getId(),
                mediator.send(new GetRequest(f.request().getId(), caller(f.request().getRequester()))).request().id());
    }

    @Test
    @Transactional
    void readFallbackTracksDelegationAndCurrentAccountAuthority() {
        Fixture f = fixture(CompletionRule.ALL_OF, 1, 2);
        Role delegateRole = new Role("DEL_" + UUID.randomUUID().toString().substring(0, 8), "Delegate", null);
        delegateRole.setPermissions(Set.of(global("request:approve")));
        delegateRole = roles.save(delegateRole);
        User target = person(delegateRole);
        User original = f.people().getFirst();
        decide.execute(f.request().getId(), DecisionAction.DELEGATE, "handover", caller(original), target.getId());
        assertThrows(ForbiddenException.class,
                () -> mediator.send(new GetRequest(f.request().getId(), caller(original))));
        assertEquals(f.request().getId(),
                mediator.send(new GetRequest(f.request().getId(), caller(target))).request().id());
        target.setActive(false);
        users.save(target);
        assertThrows(ForbiddenException.class,
                () -> mediator.send(new GetRequestParticipants(f.request().getId(), caller(target))));
        target.setActive(true);
        target.setRoles(Set.of());
        users.save(target);
        assertThrows(ForbiddenException.class,
                () -> mediator.send(new GetRequest(f.request().getId(), caller(target))));
    }

    @Test
    @Transactional
    void participationDoesNotGrantAuditAccessOrAdmitLateJoiners() {
        Fixture f = fixture(CompletionRule.ALL_OF, 1, 2);
        User late = person(f.role());
        assertThrows(ForbiddenException.class, () -> mediator.send(new GetRequest(f.request().getId(), caller(late))));
        assertThrows(ForbiddenException.class,
                () -> auditService.timeline(f.request().getId(), null, null, null, caller(f.people().getFirst())));
    }

    @Test
    @Transactional
    void rejectOnlyParticipantCanReadTheContextNeededForTheirDecision() {
        Fixture f = fixture(CompletionRule.ALL_OF, 1, 2);
        Role role = roles.findByCode(f.role().getCode()).orElseThrow();
        role.setPermissions(Set.of(global("request:reject")));
        roles.save(role);
        User user = f.people().getFirst();
        assertEquals(f.request().getId(),
                mediator.send(new GetRequest(f.request().getId(), caller(user))).request().id());
        assertThrows(ForbiddenException.class, () -> approve(f, user));
    }

    @Test
    @Transactional
    void databasePreventsRosterExtensionAfterActivation() {
        Fixture f = fixture(CompletionRule.ALL_OF, 1, 2);
        User late = person(f.role());
        assertThrows(DataAccessException.class,
                () -> participants.createInitialRoster(f.step().getId(), List.of(late.getId())));
    }

    @ParameterizedTest
    @ValueSource(strings = { "UPDATE step_participants SET user_id = user_id WHERE step_instance_id = ?",
            "DELETE FROM step_participants WHERE step_instance_id = ?" })
    @Transactional
    void assignmentHistoryCannotBeEditedOrDeleted(String sql) {
        Fixture f = fixture(CompletionRule.ALL_OF, 1, 2);
        assertThrows(DataAccessException.class, () -> jdbc.update(sql, f.step().getId()));
    }

    private String token(String username, String password) throws Exception {
        String response = mvc
            .perform(post("/api/v1/auth/token").contentType("application/json")
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return new tools.jackson.databind.ObjectMapper().readTree(response).get("token").asText();
    }

    @Test
    @Transactional
    void allOfApiAcceptsOmittedNumericThresholdAndReportsRosterMetadata() throws Exception {
        String admin = token("admin", "admin123");
        mvc.perform(post("/api/v1/workflows").header("Authorization", "Bearer " + admin)
            .contentType("application/json")
            .content("{\"code\":\"API_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase()
                    + "\",\"name\":\"All reviewers\","
                    + "\"steps\":[{\"code\":\"REVIEW\",\"name\":\"Review\",\"requiredRole\":\"ADMIN\",\"completionRule\":\"ALL_OF\"}]}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.steps[0].rule").value("ALL_OF"))
            .andExpect(jsonPath("$.steps[0].requiredApprovals").value(org.hamcrest.Matchers.nullValue()));
        Fixture f = fixture(CompletionRule.ALL_OF, 1, 2);
        String owner = token("alice", "alice123");
        mvc.perform(get("/api/v1/requests/" + f.request().getId()).header("Authorization", "Bearer " + owner))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.steps[0].rosterSnapshotted").value(true))
            .andExpect(jsonPath("$.steps[0].rosterSize").value(2))
            .andExpect(jsonPath("$.steps[0].requiredApprovals").value(2));
        mvc.perform(get("/api/v1/requests/" + f.request().getId() + "/participants").header("Authorization",
                "Bearer " + owner))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].currentAssignment").value(true));
        User stranger = person(roles.findByCode("MEMBER").orElseThrow());
        stranger.setPasswordHash(encoder.encode("other-pass"));
        users.save(stranger);
        mvc.perform(get("/api/v1/requests/" + f.request().getId() + "/participants").header("Authorization",
                "Bearer " + token(stranger.getUsername(), "other-pass")))
            .andExpect(status().isForbidden());
    }

    @Test
    void incompleteRosterCannotCommit() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        Fixture f = tx.execute(status -> fixture(CompletionRule.ALL_OF, 1, 2));
        assertNotNull(f);
        assertThrows(DataAccessException.class, () -> tx.executeWithoutResult(status -> {
            Request draft = Request.newDraft(f.request().getType(), users.findByUsername("alice").orElseThrow(),
                    Map.of(), null);
            draft.markSubmitted(java.time.Instant.now());
            draft.beginReview(java.time.Instant.now());
            requests.save(draft);
            StepInstance broken = new StepInstance(draft, f.step().getStep());
            broken.snapshotRoster(2);
            steps.save(broken);
            participants.createInitialRoster(broken.getId(), List.of(f.people().getFirst().getId()));
        }));
    }

    @Test
    void concurrentLastVotesCompleteAllOfOnceWithoutLosingASlot() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        Fixture f = tx.execute(status -> fixture(CompletionRule.ALL_OF, 1, 2));
        assertNotNull(f);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> pending = new ArrayList<>();
            for (User user : f.people())
                pending.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        approve(f, user);
                        return true;
                    }
                    catch (ObjectOptimisticLockingFailureException | ConflictException conflict) {
                        return false;
                    }
                }));
            List<User> retry = new ArrayList<>();
            for (int i = 0; i < pending.size(); i++)
                if (!pending.get(i).get(20, TimeUnit.SECONDS))
                    retry.add(f.people().get(i));
            for (User user : retry)
                approve(f, user);
        }
        tx.executeWithoutResult(status -> {
            assertEquals(RequestStatus.EXECUTED, requests.findById(f.request().getId()).orElseThrow().getStatus());
            assertEquals(2, decisions.countDistinctApprovers(f.step().getId()));
            assertEquals(2, participants.findByStepInstanceId(f.step().getId()).size());
        });
    }

}
