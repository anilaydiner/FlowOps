package com.flowops;

import com.flowops.api.dto.RequestResponse;
import com.flowops.domain.request.policy.ApprovalEligibilityPolicy.Authority;
import com.flowops.domain.request.policy.ApprovalEligibilityPolicy.Grant;
import com.flowops.domain.identity.policy.PermissionScope;
import com.flowops.application.mediator.Mediator;
import com.flowops.application.request.RequestDetailReader;
import com.flowops.application.request.model.RequestDetail;
import com.flowops.application.request.model.RequestListItem;
import com.flowops.application.request.port.ApprovalQueueQueries;
import com.flowops.application.request.port.RequestDetailQueries;
import com.flowops.application.request.port.RequestListQueries;
import com.flowops.application.request.query.ListRequests;
import com.flowops.domain.execution.ExecutionJob;
import com.flowops.domain.execution.port.ExecutionJobRepository;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.User;
import com.flowops.domain.identity.port.UserRepository;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.request.Clarification;
import com.flowops.domain.request.Decision;
import com.flowops.domain.request.DecisionAction;
import com.flowops.domain.request.Request;
import com.flowops.domain.request.RequestStatus;
import com.flowops.domain.request.StepInstance;
import com.flowops.domain.request.StepState;
import com.flowops.domain.request.port.ClarificationRepository;
import com.flowops.domain.request.port.DecisionRepository;
import com.flowops.domain.request.port.RequestRepository;
import com.flowops.domain.request.port.StepInstanceRepository;
import com.flowops.domain.request.query.RequestFilter;
import com.flowops.domain.workflow.RequestType;
import com.flowops.domain.workflow.Workflow;
import com.flowops.domain.workflow.WorkflowStep;
import com.flowops.domain.workflow.port.RequestTypeRepository;
import com.flowops.domain.workflow.port.WorkflowRepository;
import com.flowops.domain.workflow.port.WorkflowStepRepository;
import com.flowops.worker.JobWorker;
import com.flowops.worker.OutboxDispatcher;
import com.flowops.worker.SlaScanner;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
class RequestListProjectionTest extends AbstractIntegrationTest {
    // Isolate SessionFactory-wide SQL statistics from scheduled polling.
    @MockitoBean JobWorker jobWorker;
    @MockitoBean OutboxDispatcher outboxDispatcher;
    @MockitoBean SlaScanner slaScanner;
    @Autowired RequestDetailQueries detailQueries;
    @Autowired RequestDetailReader detailReader;
    @Autowired ClarificationRepository clarificationRepository;
    @Autowired ExecutionJobRepository jobRepository;
    @Autowired ApprovalQueueQueries queue;
    @Autowired WorkflowRepository workflows;
    @Autowired WorkflowStepRepository workflowSteps;
    @Autowired StepInstanceRepository stepInstances;
    @Autowired DecisionRepository decisions;
    @Autowired RequestListQueries lists;
    @Autowired RequestRepository requests;
    @Autowired RequestTypeRepository types;
    @Autowired UserRepository users;
    @Autowired Mediator mediator;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired PlatformTransactionManager transactions;
    private String typeCode;
    private CallerIdentity admin;
    private CallerIdentity alice;
    private List<RequestResponse> expected;

    @BeforeEach
    void prepareIsolatedRequests() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var owner = users.findByUsername("admin").orElseThrow();
            var other = users.findByUsername("alice").orElseThrow();
            admin = new CallerIdentity(owner.getId(), owner.getUsername());
            alice = new CallerIdentity(other.getId(), other.getUsername());
            typeCode = "READ_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            // Nullable department and nested JSON exercise the projection's left join and JSON mapping.
            RequestType type = types.save(new RequestType(typeCode, "Read projection", null, null));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("items", Arrays.asList(Map.of("amount", 12), null));
            Request first = requests.save(Request.newDraft(type, owner, payload, "projection-correlation"));
            Request second = requests.save(Request.newDraft(type, other, Map.of("amount", 20), null));
            Request third = Request.newDraft(type, owner, Map.of(), null);
            third.cancel();
            requests.save(third);
            entityManager.clear();
            expected = List.of(third.getId(), second.getId(), first.getId()).stream()
                    .map(id -> RequestResponse.from(RequestDetail.of(requests.findById(id).orElseThrow()))).toList();
        });
    }

    @Test
    void scalarProjectionMatchesExistingResponseWithoutLoadingEntities() {
        var stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            entityManager.clear();
            stats.clear();
            var result = lists.findAll(new RequestFilter(null, typeCode, null),
                    PageQuery.of(0, 2, "id", true), admin.id(), true);
            assertEquals(3, result.totalElements());
            assertEquals(2, result.getTotalPages());
            assertEquals(expected.subList(0, 2), result.content().stream().map(RequestResponse::from).toList());
            assertEquals(0, stats.getEntityLoadCount());
            assertEquals(0, stats.getCollectionFetchCount());
            assertEquals(3, stats.getPrepareStatementCount()); // header, count, batched execution summary
        });
        // Returned values remain usable after the persistence context closes.
        var tail = new TransactionTemplate(transactions).execute(status -> lists.findAll(
                new RequestFilter(null, typeCode, null), PageQuery.of(1, 2, "id", true), admin.id(), true));
        assertNotNull(tail);
        assertEquals(List.of(expected.get(2)), tail.content().stream().map(RequestResponse::from).toList());
    }

    @Test
    void mineAndMemberVisibilityAreAppliedBeforeCountingAndPaging() {
        var filter = new RequestFilter(" draft ", " " + typeCode.toLowerCase() + " ", true);
        var own = mediator.send(new ListRequests(filter, PageQuery.first(10), admin));
        assertEquals(1, own.totalElements());
        assertEquals(List.of(expected.get(2).id()), own.content().stream().map(RequestListItem::id).toList());
        var member = mediator.send(new ListRequests(new RequestFilter(null, typeCode, false), PageQuery.first(10), alice));
        assertEquals(1, member.totalElements());
        assertEquals(List.of(expected.get(1).id()), member.content().stream().map(RequestListItem::id).toList());
        var beyond = mediator.send(new ListRequests(filter, PageQuery.of(3, 1, "id", false), admin));
        assertTrue(beyond.content().isEmpty());
        assertEquals(1, beyond.totalElements());
        assertEquals(3, beyond.page());
    }

    @Test
    void listAllKeepsCombinedSortOrderAndFilters() {
        var result = mediator.send(new ListRequests(new RequestFilter(null, typeCode, false),
                new PageQuery(0, 10, List.of(new PageQuery.Order("status", false), new PageQuery.Order("id", true))), admin));
        assertEquals(3, result.totalElements());
        // CANCELLED sorts before DRAFT; matching DRAFT rows use descending ids.
        assertEquals(expected.stream().map(RequestResponse::id).toList(), result.content().stream().map(RequestListItem::id).toList());
    }
    private record QueueFixture(Request request, StepInstance step) {}

    private QueueFixture fixture(RequestType type, User owner, WorkflowStep definition,
                                 RequestStatus status, StepState state) {
        Request request = Request.newDraft(type, owner, Map.of(), null);
        if (status != RequestStatus.DRAFT) {
            request.markSubmitted(java.time.Instant.now());
            request.beginReview(java.time.Instant.now());
            if (status == RequestStatus.REJECTED) request.reject(java.time.Instant.now());
        }
        requests.save(request);
        StepInstance instance = new StepInstance(request, definition);
        instance.setRosterSnapshotted(false); // Explicit legacy fixture: no historical activation roster.
        if (state == StepState.BLOCKED) instance.block(com.flowops.domain.request.BlockReason.NO_ELIGIBLE_APPROVER, "test",
                users.findByUsername("admin").orElseThrow().getId(), "WORKFLOW_OPERATIONS", java.time.Instant.now());
        if (state == StepState.SATISFIED) instance.satisfy(owner.getId());
        stepInstances.save(instance);
        return new QueueFixture(request, instance);
    }

    @Test
    void queueProjectsAndCountsRoleOrDelegationMatchesWithoutDuplicatesOrAggregateLoads() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            status.setRollbackOnly();
            String unique = UUID.randomUUID().toString().substring(0, 8);
            User viewer = users.save(User.newUser("queue_" + unique, "Queue viewer",
                    unique + "@example.com", null, Set.of(), "unused"));
            User owner = users.findByUsername("alice").orElseThrow();
            RequestType type = types.findByCode(typeCode).orElseThrow();
            Workflow workflow = workflows.save(new Workflow("Q_" + unique, "Queue test", null));
            String role = "MATCH_" + unique;
            WorkflowStep matching = workflowSteps.save(new WorkflowStep(workflow, 1, "MATCH", "Match", role));
            WorkflowStep other = workflowSteps.save(new WorkflowStep(workflow, 2, "OTHER", "Other", "OTHER_" + unique));
            QueueFixture matched = fixture(type, owner, matching, RequestStatus.IN_REVIEW, StepState.PENDING);
            // Historical matching rows must not duplicate the request or inflate the count.
            StepInstance duplicate = new StepInstance(matched.request(), matching);
            duplicate.setRosterSnapshotted(false);
            duplicate.satisfy(owner.getId());
            stepInstances.save(duplicate);
            QueueFixture delegated = fixture(type, owner, other, RequestStatus.IN_REVIEW, StepState.PENDING);
            for (int i = 0; i < 2; i++) {
                decisions.save(new Decision(delegated.request(), delegated.step(), owner.getId(), owner.getUsername(),
                        DecisionAction.DELEGATE, "delegate", Map.of(), viewer.getId()));
            }
            fixture(type, viewer, matching, RequestStatus.IN_REVIEW, StepState.PENDING); // own request
            fixture(type, owner, matching, RequestStatus.DRAFT, StepState.PENDING);
            fixture(type, owner, matching, RequestStatus.REJECTED, StepState.PENDING);
            fixture(type, owner, matching, RequestStatus.IN_REVIEW, StepState.BLOCKED);
            fixture(type, owner, matching, RequestStatus.IN_REVIEW, StepState.SATISFIED);
            QueueFixture old = fixture(type, owner, other, RequestStatus.IN_REVIEW, StepState.SATISFIED);
            decisions.save(new Decision(old.request(), old.step(), owner.getId(), owner.getUsername(),
                    DecisionAction.DELEGATE, "old delegation", Map.of(), viewer.getId()));
            StepInstance fresh = new StepInstance(old.request(), other);
            fresh.setRosterSnapshotted(false);
            stepInstances.save(fresh); // no delegation on this pending instance
            fixture(type, owner, other, RequestStatus.IN_REVIEW, StepState.PENDING); // no matching role/delegation
            entityManager.flush();
            entityManager.clear();
            var stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
            stats.clear();
            var first = queue.findPage(PageQuery.of(0, 1, "id", true), new Authority(viewer.getId(), true, Set.of(role), null,
                    List.of(new Grant("request:approve", role, new PermissionScope(null, null)))));
            assertEquals(2, first.totalElements());
            assertEquals(List.of(delegated.request().getId()), first.content().stream().map(RequestListItem::id).toList());
            assertEquals(0, stats.getEntityLoadCount());
            assertEquals(3, stats.getPrepareStatementCount());
            var second = queue.findPage(PageQuery.of(1, 1, "id", true), new Authority(viewer.getId(), true, Set.of(role), null,
                    List.of(new Grant("request:approve", role, new PermissionScope(null, null)))));
            assertEquals(2, second.totalElements());
            assertEquals(List.of(matched.request().getId()), second.content().stream().map(RequestListItem::id).toList());
            var beyond = queue.findPage(PageQuery.of(2, 1, "id", true), new Authority(viewer.getId(), true, Set.of(role), null,
                    List.of(new Grant("request:approve", role, new PermissionScope(null, null)))));
            assertTrue(beyond.content().isEmpty());
            assertEquals(2, beyond.totalElements());
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void detailProjectionPreservesAuditAndCollectionOrderWithFixedQueryCount(int stepCount) {
        var result = new TransactionTemplate(transactions).execute(status -> {
            status.setRollbackOnly();
            User owner = users.findByUsername("alice").orElseThrow();
            User actor = users.findByUsername("admin").orElseThrow();
            RequestType type = types.findByCode(typeCode).orElseThrow();
            Request request = Request.newDraft(type, owner, Map.of("amount", "42.50"), "detail-correlation");
            request.markSubmitted(Instant.now());
            request.beginReview(java.time.Instant.now());
            requests.save(request);
            Workflow workflow = workflows.save(new Workflow("DETAIL_" + typeCode, "Detail test", null));
            for (int i = 0; i < stepCount; i++) {
                // Insert in reverse position order: the existing contract orders instances by id.
                WorkflowStep definition = workflowSteps.save(new WorkflowStep(workflow, stepCount - i,
                        "STEP_" + i, "Step " + i, "ADMIN"));
                StepInstance instance = new StepInstance(request, definition);
                instance.setRosterSnapshotted(false); // Explicit legacy fixture: no historical activation roster.
                if (i < stepCount - 1 || stepCount == 1) instance.satisfy(actor.getId());
                stepInstances.save(instance);
                decisions.save(new Decision(request, instance, actor.getId(), "historical_" + i,
                        DecisionAction.APPROVE, "approved " + i,
                        Map.of("authorization", Map.of("allowed", true, "evidence", List.of("role=ADMIN")))));
                decisions.save(new Decision(request, instance, actor.getId(), "historical_" + i,
                        DecisionAction.DELEGATE, "delegated " + i,
                        Map.of("delegatedTo", String.valueOf(owner.getId())), owner.getId()));
            }
            Instant earlier = Instant.parse("2026-01-01T00:00:00Z");
            Clarification answered = new Clarification(request.getId(), actor.getId(), "Earlier question");
            answered.setCreatedAt(earlier);
            answered.answer("Answer", owner.getId());
            clarificationRepository.save(answered);
            Clarification latest = new Clarification(request.getId(), actor.getId(), "Latest question");
            latest.setCreatedAt(earlier.plusSeconds(1));
            clarificationRepository.save(latest);
            ExecutionJob completed = new ExecutionJob("detail:" + request.getId() + ":one", request.getId(), "EXPORT", "detail-correlation");
            completed.setCreatedAt(earlier);
            LegacyRequestFixtures.approveWithoutManifest(jdbc, request.getId(), Instant.now(), true);
            var legacy = requests.findByIdForUpdate(request.getId()).orElseThrow();
            legacy.observeReconciledExecutionStart(Instant.now());
            requests.save(legacy);
            completed.freezeInput(request.getPayload());
            completed.markStarted(Instant.now(),java.util.UUID.randomUUID(),Instant.now().plusSeconds(60));
            completed.markCompleted(Map.of("reference", "result-1", "count", 2),Instant.now());
            jobRepository.save(completed);
            ExecutionJob planned = new ExecutionJob("detail:" + request.getId() + ":two", request.getId(), "OTHER", null);
            planned.setCreatedAt(earlier.plusSeconds(1));
            jobRepository.save(planned);
            entityManager.flush();
            entityManager.clear();
            RequestResponse expected = RequestResponse.from(detailReader.load(requests.findById(request.getId()).orElseThrow()));
            entityManager.clear();
            var stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
            stats.clear();
            var header = detailQueries.findRequest(request.getId()).orElseThrow();
            var view = detailQueries.load(header);
            assertEquals(expected, RequestResponse.from(view));
            assertEquals(6, stats.getPrepareStatementCount());
            assertEquals(0, stats.getEntityLoadCount());
            assertEquals(0, stats.getCollectionFetchCount());
            return view;
        });
        assertNotNull(result);
        RequestResponse response = RequestResponse.from(result); // usable after session closes
        assertEquals(stepCount, response.steps().size());
        assertEquals("STEP_0", response.steps().getFirst().code());
        for (int i = 0; i < stepCount; i++) {
            var history = response.steps().get(i).decisions();
            assertEquals(2, history.size());
            assertEquals("historical_" + i, history.getFirst().actorUsername());
            assertEquals("APPROVE", history.getFirst().action());
            assertEquals(Map.of("allowed", true, "evidence", List.of("role=ADMIN")),
                    history.getFirst().evidence().get("authorization"));
            assertEquals("DELEGATE", history.getLast().action());
            assertEquals(response.requester().id(), history.getLast().delegatedTo());
        }
        assertEquals("Latest question", response.clarifications().getFirst().question());
        assertEquals("COMPLETED", response.executionJobs().getFirst().status());
    }

    @Test
    void detailWithoutStepsSkipsDecisionQueryAndPreservesEmptyCollections() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            entityManager.clear();
            var stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
            stats.clear();
            var view = detailQueries.load(detailQueries.findRequest(expected.getFirst().id()).orElseThrow());
            assertEquals(expected.getFirst(), RequestResponse.from(view));
            assertEquals(5, stats.getPrepareStatementCount());
            assertEquals(0, stats.getEntityLoadCount());
        });
    }

}
