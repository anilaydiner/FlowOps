package com.flowops;

import com.flowops.application.notification.ApproverNotifier;
import com.flowops.application.request.ApprovalEligibilityService;
import com.flowops.application.request.lifecycle.DecideStep;
import com.flowops.application.request.port.ApprovalQueueQueries;
import com.flowops.application.request.query.GetApprovalQueue;
import com.flowops.application.mediator.Mediator;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.ForbiddenException;
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
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class ApprovalEligibilityIntegrationTest extends AbstractIntegrationTest {
    @MockitoBean JobWorker backgroundJobs;
    @MockitoBean OutboxDispatcher backgroundOutbox;
    @MockitoBean SlaScanner backgroundSla;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PermissionRepository permissions;
    @Autowired DepartmentRepository departments;
    @Autowired RequestTypeRepository types;
    @Autowired WorkflowRepository workflows;
    @Autowired WorkflowStepRepository definitions;
    @Autowired RequestRepository requests;
    @Autowired StepInstanceRepository steps;
    @Autowired StepParticipantRepository participants;
    @Autowired DecisionRepository decisions;
    @Autowired ApprovalEligibilityService eligibility;
    @Autowired ApprovalQueueQueries queue;
    @Autowired ApproverNotifier notifier;
    @Autowired com.flowops.application.request.workflow.StepMaterializer materializer;
    @Autowired DecideStep decide;
    @Autowired Mediator mediator;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    private record Fixture(Request request, StepInstance step, User actor, User second) {
        CallerIdentity caller() { return new CallerIdentity(actor.getId(), actor.getUsername(), Set.of("STALE_CLAIM")); }
    }

    private Fixture fixture(ScopeType scope, String value, Map<String, Object> payload, int votes) {
        return fixture(scope, value, payload, votes, 1);
    }

    private Fixture fixture(ScopeType scope, String value, Map<String, Object> payload, int votes, int currentPosition) {
        String key = UUID.randomUUID().toString().substring(0, 8);
        String code = "EL_" + key;
        if (value == null) value = switch (scope == null ? "GLOBAL" : scope.name()) {
            case "REQUEST_TYPE" -> code.toLowerCase(Locale.ROOT);
            case "STEP" -> "review";
            case "THRESHOLD" -> "100";
            default -> null;
        };
        Long permissionId = jdbc.queryForObject("""
                INSERT INTO permissions(code, name, scope_type, scope_value)
                VALUES (?, 'Eligibility test', ?, ?) RETURNING id
                """, Long.class, "request:approve:" + key, scope == null ? null : scope.name(), value);
        Role role = new Role("EL_" + key, "Eligibility", null);
        role.setPermissions(Set.of(permissions.findById(permissionId).orElseThrow(),
                permissions.findByCode("request:queue").orElseThrow()));
        role = roles.save(role);
        var department = departments.findByCode("FINANCE").orElseThrow();
        User actor = users.save(User.newUser("el_a_" + key, "Actor", null, department, Set.of(role), "unused"));
        User second = users.save(User.newUser("el_b_" + key, "Second", null, department, Set.of(role), "unused"));
        Workflow workflow = workflows.save(new Workflow(code, "Eligibility", null));
        WorkflowStep definition = new WorkflowStep(workflow, currentPosition, "REVIEW", "Review", role.getCode());
        definition.configure(votes == 1 ? CompletionRule.ANY_OF : CompletionRule.K_OF_N, votes, Map.of(), null, null, false);
        definition = definitions.save(definition);
        RequestType type = new RequestType(code, "Eligibility", null, department);
        type.setDefaultWorkflow(workflow);
        type = types.save(type);
        Request request = Request.newDraft(type, users.findByUsername("alice").orElseThrow(), payload, null);
        request.markSubmitted(Instant.now());
        request.beginReview(java.time.Instant.now());
        requests.save(request);
        StepInstance step = new StepInstance(request, definition);
        step.snapshotRoster(2);
        steps.save(step);
        participants.createInitialRoster(step.getId(), List.of(actor.getId(), second.getId()));
        return new Fixture(request, step, actor, second);
    }

    @Test @Transactional
    void duplicateApprovalCannotSatisfyTwoPersonRuleAndPartialVoteAdvancesVersion() {
        Fixture f = fixture(null, null, Map.of("amount", 100), 2);
        assertQueueMatchesEligibility(f, f.actor(), true);
        long version = f.request().getVersion();
        Request partial = decide.execute(f.request().getId(), DecisionAction.APPROVE, "first", f.caller());
        assertEquals(RequestStatus.IN_REVIEW, partial.getStatus());
        assertTrue(partial.getVersion() > version);
        assertThrows(ConflictException.class, () -> decide.execute(f.request().getId(), DecisionAction.APPROVE, "again", f.caller()));
        assertEquals(1, decisions.countDistinctApprovers(f.step().getId()));
        assertQueueMatchesEligibility(f, f.actor(), false);
        assertQueueMatchesEligibility(f, f.second(), true);
        Request complete = decide.execute(f.request().getId(), DecisionAction.APPROVE, "second person",
                new CallerIdentity(f.second().getId(), f.second().getUsername()));
        assertEquals(RequestStatus.EXECUTED, complete.getStatus());
        assertEquals(2, decisions.countDistinctApprovers(f.step().getId()));
    }

    private void assertQueueMatchesEligibility(Fixture fixture, User actor, boolean expected) {
        var request = requests.findById(fixture.request().getId()).orElseThrow();
        var step = steps.findTopByRequestIdAndStateOrderById(request.getId(), StepState.PENDING).orElseThrow();
        assertEquals(expected, eligibility.evaluate(actor, request, step, DecisionAction.APPROVE,
                eligibility.history(step), false).allowed());
        var page = queue.findPage(PageQuery.first(1), eligibility.authority(actor.getId(), DecisionAction.APPROVE));
        assertEquals(expected ? 1 : 0, page.totalElements());
        assertEquals(expected ? List.of(request.getId()) : List.of(), page.content().stream().map(item -> item.id()).toList());
    }

    @Test @Transactional
    void databaseRejectsDuplicateVotesEvenWhenApplicationGateIsBypassed() {
        Fixture f = fixture(null, null, Map.of(), 2);
        decisions.save(new Decision(f.request(), f.step(), f.actor().getId(), f.actor().getUsername(),
                DecisionAction.APPROVE, "one", Map.of()));
        assertEquals(1, decisions.countDistinctApprovers(f.step().getId()));
        assertThrows(DataIntegrityViolationException.class, () -> decisions.save(new Decision(
                f.request(), f.step(), f.actor().getId(), f.actor().getUsername(), DecisionAction.APPROVE, "two", Map.of())));
    }

    @Test @Transactional
    void ledgerCannotAttributeAnApprovalToAnotherPerson() {
        Fixture f = fixture(null, null, Map.of(), 2);
        Decision source = decisions.save(new Decision(f.request(), f.step(), f.actor().getId(), f.actor().getUsername(),
                DecisionAction.APPROVE, "source", Map.of()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO step_approval_votes(step_instance_id, actor_id, decision_id, created_at)
                SELECT step_instance_id, ?, id, created_at FROM decisions WHERE id = ?
                """, f.second().getId(), source.getId()));
    }

    @ParameterizedTest @EnumSource(ScopeType.class) @Transactional
    void queueAndDomainAgreeForEveryScopeBeforePaging(ScopeType scope) {
        Fixture f = fixture(scope, null, Map.of("amount", "100.0"), 1);
        for (Map<String, Object> payload : List.<Map<String, Object>>of(Map.of("amount", 101), Map.of(), Map.of("amount", "bad"))) {
            Request r = Request.newDraft(f.request().getType(), f.request().getRequester(), payload, null);
            r.markSubmitted(Instant.now()); r.beginReview(java.time.Instant.now()); requests.save(r);
            StepInstance instance = new StepInstance(r, f.step().getStep());
            instance.snapshotRoster(2);
            steps.save(instance);
            participants.createInitialRoster(instance.getId(), List.of(f.actor().getId(), f.second().getId()));
        }
        var authority = eligibility.authority(f.actor().getId(), DecisionAction.APPROVE);
        var expected = requestsFor(f).stream().filter(r -> {
            var step = steps.findTopByRequestIdAndStateOrderById(r.getId(), StepState.PENDING).orElseThrow();
            return eligibility.evaluate(f.actor(), r, step, DecisionAction.APPROVE, eligibility.history(step), false).allowed();
        }).map(Request::getId).sorted().toList();
        for (int page = 0; page <= expected.size(); page++) {
            var actual = queue.findPage(PageQuery.of(page, 1, "id", false), authority);
            assertEquals(expected.size(), actual.totalElements());
            assertEquals(page < expected.size() ? List.of(expected.get(page)) : List.of(), actual.content().stream().map(x -> x.id()).toList());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"NaN", "Infinity", "1e9999", "1e-9999", "bad", "1_0", "0x1p1", " 100 ", "1e2"}) @Transactional
    void thresholdAmountParsingAgreesInSqlAndDomain(String amount) {
        Fixture f = fixture(ScopeType.THRESHOLD, "100", Map.of("amount", amount), 1);
        boolean expected = eligibility.evaluate(f.actor(), f.request(), f.step(), DecisionAction.APPROVE, List.of(), false).allowed();
        assertEquals(expected ? 1 : 0, queue.findPage(PageQuery.first(10),
                eligibility.authority(f.actor().getId(), DecisionAction.APPROVE)).totalElements());
    }

    private List<Request> requestsFor(Fixture f) {
        return jdbc.queryForList("SELECT id FROM requests WHERE type_id = ? ORDER BY id", Long.class,
                f.request().getType().getId()).stream().map(id -> requests.findById(id).orElseThrow()).toList();
    }

    @Test @Transactional
    void currentRolesNotJwtClaimsControlQueueAndGlobalPermissionDoesNotBypassParticipation() {
        Fixture f = fixture(null, null, Map.of(), 1);
        assertEquals(1, mediator.send(new GetApprovalQueue(PageQuery.first(10), f.caller())).totalElements());
        User admin = users.findByUsername("admin").orElseThrow();
        assertThrows(ForbiddenException.class, () -> decide.execute(f.request().getId(), DecisionAction.APPROVE,
                "outside role", new CallerIdentity(admin.getId(), admin.getUsername(), Set.of(f.step().getStep().getRequiredRole()))));
        f.actor().setActive(false); users.save(f.actor());
        assertEquals(0, mediator.send(new GetApprovalQueue(PageQuery.first(10), f.caller())).totalElements());
        assertThrows(ForbiddenException.class, () -> decide.execute(f.request().getId(), DecisionAction.APPROVE, "disabled", f.caller()));
    }

    @Test @Transactional
    void removedParticipationRoleCannotBeRestoredByStaleTokenClaims() {
        Fixture f = fixture(null, null, Map.of(), 1);
        Role inbox = new Role("IN_" + UUID.randomUUID().toString().substring(0, 8), "Inbox only", null);
        inbox.setPermissions(Set.of(permissions.findByCode("request:queue").orElseThrow()));
        inbox = roles.save(inbox);
        var stale = new CallerIdentity(f.actor().getId(), f.actor().getUsername(), f.actor().roleCodes());
        f.actor().setRoles(Set.of(inbox)); users.save(f.actor());
        assertEquals(0, mediator.send(new GetApprovalQueue(PageQuery.first(10), stale)).totalElements());
        assertThrows(ForbiddenException.class, () -> decide.execute(f.request().getId(), DecisionAction.APPROVE, "revoked", stale));
    }

    @Test @Transactional
    void participantReadFallbackRechecksTheCurrentDepartmentScope() {
        Fixture f = fixture(ScopeType.DEPARTMENT, null, Map.of(), 1);
        var query = new com.flowops.application.request.query.GetRequest(f.request().getId(), f.caller());
        assertEquals(f.request().getId(), mediator.send(query).request().id());
        f.actor().updateProfile(f.actor().getDisplayName(), f.actor().getEmail(), departments.findByCode("IT").orElseThrow());
        users.save(f.actor());
        assertThrows(ForbiddenException.class, () -> mediator.send(query));
    }

    @Test @Transactional
    void materializationAndNotificationsRequirePermissionAsWellAsRoleAndScope() {
        Fixture f = fixture(ScopeType.THRESHOLD, "100", Map.of("amount", 100), 1);
        assertEquals(2, notifier.notifyEligibleApprovers(f.request(), null));
        assertEquals(2, eligibility.eligibleAtActivation(f.request(), f.step()));
        Request missing = Request.newDraft(f.request().getType(), f.request().getRequester(), Map.of(), null);
        missing.markSubmitted(Instant.now()); requests.save(missing);
        materializer.materializeNext(missing, f.step().getStep().getWorkflow(), null);
        assertEquals(1, steps.countByRequestIdAndState(missing.getId(), StepState.BLOCKED));
        assertEquals(0, notifier.notifyEligibleApprovers(missing, null));
        Role required = roles.findByCode(f.step().getStep().getRequiredRole()).orElseThrow();
        required.setPermissions(Set.of(permissions.findByCode("request:queue").orElseThrow()));
        roles.save(required);
        assertEquals(0, eligibility.eligibleAtActivation(f.request(), f.step()));
        assertThrows(ForbiddenException.class, () -> decide.execute(f.request().getId(), DecisionAction.APPROVE, "no permission", f.caller()));
    }

    @Test @Transactional
    void malformedThresholdDoesNotPoisonIndependentMatchingGrantOrQueueCount() {
        Fixture f = fixture(ScopeType.THRESHOLD, "not-a-number", Map.of("amount", 100), 1);
        assertEquals(0, queue.findPage(PageQuery.first(10), eligibility.authority(f.actor().getId(), DecisionAction.APPROVE)).totalElements());
        Role role = roles.findByCode(f.step().getStep().getRequiredRole()).orElseThrow();
        var grants = new LinkedHashSet<>(role.getPermissions());
        grants.add(permissions.findAll().stream().filter(p -> p.getCode().equals("request:approve") && p.getScopeType() == null).findFirst().orElseThrow());
        role.setPermissions(grants); roles.save(role);
        assertEquals(1, queue.findPage(PageQuery.first(10), eligibility.authority(f.actor().getId(), DecisionAction.APPROVE)).totalElements());
        assertEquals(RequestStatus.EXECUTED, decide.execute(f.request().getId(), DecisionAction.APPROVE, "independent grant", f.caller()).getStatus());
    }

    @Test @Transactional
    void delegationTransfersParticipationAndDoesNotGiveTheDelegatorAnotherVote() {
        Fixture f = fixture(null, null, Map.of(), 2);
        User outsider = users.findByUsername("admin").orElseThrow();
        decide.execute(f.request().getId(), DecisionAction.DELEGATE, "transfer", f.caller(), outsider.getId());
        assertFalse(eligibility.evaluate(f.actor(), requests.findById(f.request().getId()).orElseThrow(), f.step(),
                DecisionAction.APPROVE, eligibility.history(f.step()), false).allowed());
        assertThrows(ForbiddenException.class, () -> decide.execute(f.request().getId(), DecisionAction.APPROVE, "delegated away", f.caller()));
        var delegate = new CallerIdentity(outsider.getId(), outsider.getUsername());
        assertEquals(RequestStatus.IN_REVIEW, decide.execute(f.request().getId(), DecisionAction.APPROVE, "delegate vote", delegate).getStatus());
        assertEquals(0, queue.findPage(PageQuery.first(10), eligibility.authority(f.actor().getId(), DecisionAction.APPROVE)).totalElements());
        assertEquals(1, decisions.countDistinctApprovers(f.step().getId()));
    }

    @Test @Transactional
    void anOlderRunMustVisitAnEarlierDefinitionItMissed() {
        Fixture f = fixture(null, null, Map.of(), 1, 3);
        var workflow = f.step().getStep().getWorkflow();
        definitions.save(new WorkflowStep(workflow, 2, "MISSED_REVIEW", "Missed review", f.step().getRequiredRole()));

        assertEquals(RequestStatus.IN_REVIEW, decide.execute(f.request().getId(), DecisionAction.APPROVE,
                "Position three was already active", f.caller()).getStatus());
        var pending = steps.findTopByRequestIdAndStateOrderById(f.request().getId(), StepState.PENDING).orElseThrow();
        assertEquals(2, pending.getPosition());
        assertEquals(RequestStatus.EXECUTED, decide.execute(f.request().getId(), DecisionAction.APPROVE,
                "Approved the recovered review", f.caller()).getStatus());
    }

    @Test @Transactional
    void staleStepAndVersionNeverApplyToNextStep() {
        Fixture f = fixture(null, null, Map.of(), 1);
        definitions.save(new WorkflowStep(f.step().getStep().getWorkflow(), 2, "SECOND", "Second", f.step().getStep().getRequiredRole()));
        Long originalVersion = f.request().getVersion();
        decide.execute(f.request().getId(), DecisionAction.APPROVE, "first step", f.caller(), null, f.step().getId(), originalVersion);
        assertThrows(ConflictException.class, () -> decide.execute(f.request().getId(), DecisionAction.APPROVE, "stale target",
                new CallerIdentity(f.second().getId(), f.second().getUsername()), null, f.step().getId(), originalVersion));
        var pending = steps.findTopByRequestIdAndStateOrderById(f.request().getId(), StepState.PENDING).orElseThrow();
        assertNotEquals(f.step().getId(), pending.getId());
        assertEquals(0, decisions.countDistinctApprovers(pending.getId()));
    }

    @Test
    void concurrentSamePersonApprovalsCommitExactlyOneVote() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        Fixture f = tx.execute(status -> fixture(null, null, Map.of(), 2));
        assertNotNull(f);
        CyclicBarrier start = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) futures.add(executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                try {
                    decide.execute(f.request().getId(), DecisionAction.APPROVE, "race", f.caller(), null,
                            f.step().getId(), f.request().getVersion());
                    return true;
                } catch (ConflictException | ObjectOptimisticLockingFailureException expected) { return false; }
            }));
            int successes = 0;
            for (Future<Boolean> future : futures) if (future.get(20, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
        }
        tx.executeWithoutResult(status -> {
            assertEquals(1, decisions.countDistinctApprovers(f.step().getId()));
            assertEquals(1, decisions.findByStepInstanceIdOrderById(f.step().getId()).size());
            assertEquals(RequestStatus.IN_REVIEW, requests.findById(f.request().getId()).orElseThrow().getStatus());
        });
    }
}
