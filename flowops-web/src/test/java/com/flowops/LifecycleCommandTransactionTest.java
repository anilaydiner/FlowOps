package com.flowops;

import com.flowops.application.event.DomainEventPublisher;
import com.flowops.application.mediator.Mediator;
import com.flowops.application.notification.outbox.port.OutboxMessageRepository;
import com.flowops.application.request.RequestDetailReader;
import com.flowops.application.request.command.CreateDraftCommand;
import com.flowops.application.request.command.DecideStepCommand;
import com.flowops.application.request.command.SubmitRequestCommand;
import com.flowops.application.request.model.RequestDetail;
import com.flowops.domain.execution.port.ExecutionJobRepository;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.port.UserRepository;
import com.flowops.domain.request.DecisionAction;
import com.flowops.domain.request.Request;
import com.flowops.domain.request.RequestStatus;
import com.flowops.domain.request.StepState;
import com.flowops.domain.request.event.RequestApproved;
import com.flowops.domain.request.port.DecisionRepository;
import com.flowops.domain.request.port.RequestRepository;
import com.flowops.domain.request.port.StepInstanceRepository;
import com.flowops.worker.JobWorker;
import com.flowops.worker.OutboxDispatcher;
import com.flowops.worker.SlaScanner;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** No test transaction: commands must commit or roll back through their actual Spring proxies. */
class LifecycleCommandTransactionTest extends AbstractIntegrationTest {
    // These contexts exercise commands/API reads, not scheduled delivery. Other cached
    // contexts share PostgreSQL, so polling here would race the manual failure drills.
    @MockitoBean JobWorker backgroundJobs;
    @MockitoBean OutboxDispatcher backgroundOutbox;
    @MockitoBean SlaScanner backgroundSla;

    @Autowired Mediator mediator;
    @Autowired DomainEventPublisher events;
    @Autowired com.flowops.domain.execution.port.ExecutionPlanRepository plans;
    @Autowired UserRepository users;
    @Autowired RequestRepository requests;
    @Autowired StepInstanceRepository steps;
    @Autowired DecisionRepository decisions;
    @Autowired ExecutionJobRepository jobs;
    @Autowired OutboxMessageRepository outbox;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean RequestDetailReader details;

    private CallerIdentity caller(String username) {
        return new TransactionTemplate(transactions).execute(status -> {
            var user = users.findByUsername(username).orElseThrow();
            return new CallerIdentity(user.getId(), user.getUsername());
        });
    }

    private Request draft(CallerIdentity owner) {
        return mediator.send(new CreateDraftCommand("EXPENSE",
                Map.of("amount", 150, "description", "command transaction test"), owner)).request();
    }

    private void successfulDetailAssembly() {
        when(details.load(any(Request.class))).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return RequestDetail.of(invocation.getArgument(0));
        });
    }

    @Test
    void submitResponseFailureRollsBackRequestStepsAndOutbox() {
        CallerIdentity owner = caller("alice");
        Request draft = draft(owner);
        RuntimeException failure = new IllegalStateException("detail assembly failed");
        when(details.load(any(Request.class))).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> mediator.send(new SubmitRequestCommand(draft.getId(), owner))));

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Request stored = requests.findById(draft.getId()).orElseThrow();
            assertEquals(RequestStatus.DRAFT, stored.getStatus());
            assertEquals(draft.getVersion(), stored.getVersion());
            assertNull(stored.getSubmittedAt());
            assertTrue(steps.findByRequestIdOrderById(draft.getId()).isEmpty());
            assertTrue(outbox.findByKeyPrefix("req:" + draft.getId() + ":").isEmpty());
        });
    }

    @Test
    void approvalResponseFailureRollsBackDecisionStepsJobsAndNewOutboxMessages() {
        CallerIdentity owner = caller("alice");
        Request draft = draft(owner);
        successfulDetailAssembly();
        Request submitted = mediator.send(new SubmitRequestCommand(draft.getId(), owner)).request();
        var before = new TransactionTemplate(transactions).execute(status ->
                outbox.findByKeyPrefix("req:" + draft.getId() + ":").stream().map(m -> m.getId()).toList());
        RuntimeException failure = new IllegalStateException("detail assembly failed");
        when(details.load(any(Request.class))).thenThrow(failure);

        CallerIdentity approver = caller("admin");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> mediator.send(
                new DecideStepCommand(draft.getId(), DecisionAction.APPROVE, "approved", null, approver))));

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Request stored = requests.findById(draft.getId()).orElseThrow();
            assertEquals(RequestStatus.IN_REVIEW, stored.getStatus());
            assertEquals(submitted.getVersion(), stored.getVersion());
            var instances = steps.findByRequestIdOrderById(draft.getId());
            assertEquals(1, instances.size());
            assertEquals(StepState.PENDING, instances.getFirst().getState());
            assertTrue(decisions.findByStepInstanceIdOrderById(instances.getFirst().getId()).isEmpty());
            assertTrue(jobs.findByRequestIdOrderByCreatedAtAsc(draft.getId()).isEmpty());
            assertEquals(before, outbox.findByKeyPrefix("req:" + draft.getId() + ":")
                    .stream().map(m -> m.getId()).toList());
        });
    }

    @Test
    void approvalEventsRequireAnExistingTransaction() {
        assertThrows(IllegalTransactionStateException.class,
                () -> events.publish(new RequestApproved(-1L,java.util.UUID.randomUUID(),com.flowops.domain.request.ApprovalEvidence.allSkipped(null,null))));
    }

    @Test
    void successfulApprovalCommitsRequestDecisionJobsAndOutbox() {
        CallerIdentity owner = caller("alice");
        Request draft = draft(owner);
        successfulDetailAssembly();
        mediator.send(new SubmitRequestCommand(draft.getId(), owner));
        mediator.send(new DecideStepCommand(draft.getId(), DecisionAction.APPROVE,
                "approved", null, caller("admin")));

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertEquals(RequestStatus.APPROVED, requests.findById(draft.getId()).orElseThrow().getStatus());
            var current = steps.findByRequestIdOrderById(draft.getId()).getFirst();
            assertEquals(StepState.SATISFIED, current.getState());
            assertEquals(1, decisions.findByStepInstanceIdOrderById(current.getId()).size());
            assertFalse(jobs.findByRequestIdOrderByCreatedAtAsc(draft.getId()).isEmpty());
            assertFalse(outbox.findByKeyPrefix("req:" + draft.getId() + ":").isEmpty());
            var messagesBefore = outbox.findByKeyPrefix("req:" + draft.getId() + ":")
                    .stream().map(m -> m.getId()).toList();
            var jobsBefore = jobs.findByRequestIdOrderByCreatedAtAsc(draft.getId())
                    .stream().map(job -> job.getId()).toList();
            var plan=plans.findByRequestId(draft.getId()).orElseThrow();
            events.publish(new RequestApproved(draft.getId(),plan.id(),plan.approval()));
            assertEquals(messagesBefore, outbox.findByKeyPrefix("req:" + draft.getId() + ":")
                    .stream().map(m -> m.getId()).toList());
            assertEquals(jobsBefore, jobs.findByRequestIdOrderByCreatedAtAsc(draft.getId())
                    .stream().map(job -> job.getId()).toList());
        });
    }
}
