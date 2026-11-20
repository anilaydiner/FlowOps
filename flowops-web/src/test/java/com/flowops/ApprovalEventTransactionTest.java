package com.flowops;

import com.flowops.application.event.DomainEventHandler;
import com.flowops.application.mediator.Mediator;
import com.flowops.application.notification.outbox.port.OutboxMessageRepository;
import com.flowops.application.request.command.CreateDraftCommand;
import com.flowops.application.request.command.DecideStepCommand;
import com.flowops.application.request.command.SubmitRequestCommand;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real event handlers run first; a later subscriber fails before the approval can commit.
 */
@Import(ApprovalEventTransactionTest.FailureConfiguration.class)
class ApprovalEventTransactionTest extends AbstractIntegrationTest {

    // These contexts exercise commands/API reads, not scheduled delivery. Other cached
    // contexts share PostgreSQL, so polling here would race the manual failure drills.
    @MockitoBean
    JobWorker backgroundJobs;

    @MockitoBean
    OutboxDispatcher backgroundOutbox;

    @MockitoBean
    SlaScanner backgroundSla;

    @Autowired
    Mediator mediator;

    @Autowired
    UserRepository users;

    @Autowired
    RequestRepository requests;

    @Autowired
    StepInstanceRepository steps;

    @Autowired
    DecisionRepository decisions;

    @Autowired
    ExecutionJobRepository jobs;

    @Autowired
    OutboxMessageRepository outbox;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    com.flowops.application.audit.port.LifecycleAuditRepository auditHistory;

    private CallerIdentity caller(String username) {
        return new TransactionTemplate(transactions).execute(status -> {
            var user = users.findByUsername(username).orElseThrow();
            return new CallerIdentity(user.getId(), user.getUsername());
        });
    }

    @Test
    void laterSubscriberFailureRollsBackApprovalAndAllEarlierSubscriberWrites() {
        CallerIdentity owner = caller("alice");
        Request draft = mediator
            .send(new CreateDraftCommand("EXPENSE", Map.of("amount", 150, "description", "event rollback"), owner))
            .request();
        Request submitted = mediator.send(new SubmitRequestCommand(draft.getId(), owner)).request();
        var before = new TransactionTemplate(transactions).execute(
                status -> outbox.findByKeyPrefix("req:" + draft.getId() + ":").stream().map(m -> m.getId()).toList());

        var auditBefore = auditHistory.findByRequestId(draft.getId());
        CallerIdentity approver = caller("admin");
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> mediator
            .send(new DecideStepCommand(draft.getId(), DecisionAction.APPROVE, "approved", null, approver)));
        assertEquals("approval subscriber failed", failure.getMessage());

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Request stored = requests.findById(draft.getId()).orElseThrow();
            assertEquals(RequestStatus.IN_REVIEW, stored.getStatus());
            assertEquals(auditBefore, auditHistory.findByRequestId(draft.getId()));
            assertEquals(submitted.getVersion(), stored.getVersion());
            var instances = steps.findByRequestIdOrderById(draft.getId());
            assertEquals(1, instances.size());
            assertEquals(StepState.PENDING, instances.getFirst().getState());
            assertTrue(decisions.findByStepInstanceIdOrderById(instances.getFirst().getId()).isEmpty());
            assertEquals(0, decisions.countDistinctApprovers(instances.getFirst().getId()));
            assertTrue(jobs.findByRequestIdOrderByCreatedAtAsc(draft.getId()).isEmpty());
            assertEquals(before,
                    outbox.findByKeyPrefix("req:" + draft.getId() + ":").stream().map(m -> m.getId()).toList());
        });
    }

    @TestConfiguration
    static class FailureConfiguration {

        @Bean
        @Order(300)
        DomainEventHandler<RequestApproved> failingApprovalSubscriber(ExecutionJobRepository jobs,
                OutboxMessageRepository outbox, RequestRepository requests) {
            return new DomainEventHandler<>() {
                public Class<RequestApproved> eventType() {
                    return RequestApproved.class;
                }

                public void handle(RequestApproved event) {
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                    assertFalse(jobs.findByRequestIdOrderByCreatedAtAsc(event.requestId()).isEmpty());
                    Request request = requests.findById(event.requestId()).orElseThrow();
                    assertEquals(RequestStatus.APPROVED, request.getStatus());
                    Long owner = request.getRequester().getId();
                    assertTrue(outbox.findByIdempotencyKey("req:" + event.requestId() + ":NOTIFY_APPROVE:" + owner)
                        .isPresent());
                    assertTrue(
                            outbox.findByIdempotencyKey("req:" + event.requestId() + ":APPROVED:" + owner).isPresent());
                    throw new IllegalStateException("approval subscriber failed");
                }
            };
        }

    }

}
