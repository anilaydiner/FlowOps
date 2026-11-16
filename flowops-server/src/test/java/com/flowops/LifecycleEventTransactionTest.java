package com.flowops;

import com.flowops.application.event.DomainEventHandler;
import com.flowops.application.event.DomainEventPublisher;
import com.flowops.application.mediator.Mediator;
import com.flowops.application.notification.outbox.port.OutboxMessageRepository;
import com.flowops.application.request.command.CreateDraftCommand;
import com.flowops.application.request.command.DecideStepCommand;
import com.flowops.application.request.command.SubmitRequestCommand;
import com.flowops.domain.event.DomainEvent;
import com.flowops.domain.execution.port.ExecutionJobRepository;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.User;
import com.flowops.domain.identity.port.RoleRepository;
import com.flowops.domain.identity.port.UserRepository;
import com.flowops.domain.request.DecisionAction;
import com.flowops.domain.request.Request;
import com.flowops.domain.request.RequestStatus;
import com.flowops.domain.request.StepState;
import com.flowops.domain.request.event.DecisionDelegated;
import com.flowops.domain.request.event.RequestRejected;
import com.flowops.domain.request.event.RequestSubmitted;
import com.flowops.domain.request.port.DecisionRepository;
import com.flowops.domain.request.port.RequestRepository;
import com.flowops.domain.request.port.StepInstanceRepository;
import com.flowops.worker.JobWorker;
import com.flowops.worker.OutboxDispatcher;
import com.flowops.worker.SlaScanner;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

@Import(LifecycleEventTransactionTest.ProbeConfiguration.class)
class LifecycleEventTransactionTest extends AbstractIntegrationTest {
    // These contexts exercise commands/API reads, not scheduled delivery. Other cached
    // contexts share PostgreSQL, so polling here would race the manual failure drills.
    @MockitoBean JobWorker backgroundJobs;
    @MockitoBean OutboxDispatcher backgroundOutbox;
    @MockitoBean SlaScanner backgroundSla;

    enum Kind { SUBMITTED, REJECTED, DELEGATED }

    @Autowired Mediator mediator;
    @Autowired DomainEventPublisher events;
    @Autowired RequestRepository requests;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired StepInstanceRepository steps;
    @Autowired DecisionRepository decisions;
    @Autowired ExecutionJobRepository jobs;
    @Autowired OutboxMessageRepository outbox;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EventProbe probe;

    @BeforeEach
    void resetProbe() {
        probe.failOn = null;
        probe.observed.clear();
    }

    private CallerIdentity caller(String username) {
        return new TransactionTemplate(transactions).execute(status -> {
            User user = users.findByUsername(username).orElseThrow();
            return new CallerIdentity(user.getId(), user.getUsername());
        });
    }

    private Long delegatee(Kind kind) {
        if (kind != Kind.DELEGATED) return null;
        return new TransactionTemplate(transactions).execute(status -> {
            String name = "delegate_" + UUID.randomUUID().toString().substring(0, 8);
            User user = User.newUser(name, "Delegate", name + "@example.com", null,
                    Set.of(roles.findByCode("ADMIN").orElseThrow()), "unused");
            return users.save(user).getId();
        });
    }

    private Request prepare(Kind kind, CallerIdentity owner) {
        Request draft = mediator.send(new CreateDraftCommand("EXPENSE",
                Map.of("amount", 150, "description", "lifecycle event test"), owner)).request();
        return kind == Kind.SUBMITTED ? draft
                : mediator.send(new SubmitRequestCommand(draft.getId(), owner)).request();
    }

    private void perform(Kind kind, Long id, CallerIdentity owner, Long delegatee) {
        if (kind == Kind.SUBMITTED) {
            mediator.send(new SubmitRequestCommand(id, owner));
        } else {
            mediator.send(new DecideStepCommand(id,
                    kind == Kind.REJECTED ? DecisionAction.REJECT : DecisionAction.DELEGATE,
                    "test justification", delegatee, caller("admin")));
        }
    }

    private List<Long> messageIds(Long id) {
        return outbox.findByKeyPrefix("req:" + id + ":").stream().map(message -> message.getId()).toList();
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void subscriberFailureRollsBackStateHistoryAndNotifications(Kind kind) {
        CallerIdentity owner = caller("alice");
        Long delegatee = delegatee(kind);
        Request before = prepare(kind, owner);
        List<Long> messagesBefore = new TransactionTemplate(transactions).execute(status -> messageIds(before.getId()));
        probe.failOn = kind;

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> perform(kind, before.getId(), owner, delegatee));
        assertEquals("lifecycle subscriber failed", failure.getMessage());
        assertNotNull(probe.observed.get(kind));

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Request stored = requests.findById(before.getId()).orElseThrow();
            assertEquals(before.getStatus(), stored.getStatus());
            assertEquals(before.getVersion(), stored.getVersion());
            assertEquals(before.getSubmittedAt(), stored.getSubmittedAt());
            var instances = steps.findByRequestIdOrderById(before.getId());
            assertEquals(kind == Kind.SUBMITTED ? 0 : 1, instances.size());
            for (var instance : instances) {
                assertEquals(StepState.PENDING, instance.getState());
                assertTrue(decisions.findByStepInstanceIdOrderById(instance.getId()).isEmpty());
            }
            assertTrue(jobs.findByRequestIdOrderByCreatedAtAsc(before.getId()).isEmpty());
            assertEquals(messagesBefore, messageIds(before.getId()));
        });
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void successfulEventsCommitAndReplayWithoutDuplicateNotifications(Kind kind) {
        CallerIdentity owner = caller("alice");
        Long delegatee = delegatee(kind);
        Request before = prepare(kind, owner);
        perform(kind, before.getId(), owner, delegatee);
        DomainEvent event = probe.observed.get(kind);
        assertNotNull(event);

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Request stored = requests.findById(before.getId()).orElseThrow();
            assertEquals(kind == Kind.REJECTED ? RequestStatus.REJECTED : RequestStatus.IN_REVIEW, stored.getStatus());
            var instances = steps.findByRequestIdOrderById(before.getId());
            assertEquals(1, instances.size());
            assertEquals(StepState.PENDING, instances.getFirst().getState());
            var recorded = decisions.findByStepInstanceIdOrderById(instances.getFirst().getId());
            assertEquals(kind == Kind.SUBMITTED ? 0 : 1, recorded.size());
            if (kind == Kind.DELEGATED) assertEquals(delegatee, recorded.getFirst().getDelegatedTo());
            List<Long> messagesBefore = messageIds(before.getId());
            events.publish(event);
            assertEquals(messagesBefore, messageIds(before.getId()));
            assertTrue(jobs.findByRequestIdOrderByCreatedAtAsc(before.getId()).isEmpty());
        });
    }

    static class EventProbe {
        Kind failOn;
        final Map<Kind, DomainEvent> observed = new EnumMap<>(Kind.class);
        private final RequestRepository requests;
        private final OutboxMessageRepository outbox;

        EventProbe(RequestRepository requests, OutboxMessageRepository outbox) {
            this.requests = requests;
            this.outbox = outbox;
        }

        void observe(Kind kind, DomainEvent event, Long id, Long recipient, String feed, String email) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            Request request = requests.findById(id).orElseThrow();
            Long target = recipient != null ? recipient : request.getRequester().getId();
            assertTrue(outbox.findByIdempotencyKey("req:" + id + ":" + feed + ":" + target).isPresent());
            assertTrue(outbox.findByIdempotencyKey("req:" + id + ":" + email + ":" + target).isPresent());
            if (kind == Kind.SUBMITTED) {
                assertTrue(outbox.findByKeyPrefix("req:" + id + ":NOTIFY_APPROVER_REQUEST:").size() > 0);
            }
            observed.put(kind, event);
            if (failOn == kind) throw new IllegalStateException("lifecycle subscriber failed");
        }
    }

    @TestConfiguration
    static class ProbeConfiguration {
        @Bean
        EventProbe eventProbe(RequestRepository requests, OutboxMessageRepository outbox) {
            return new EventProbe(requests, outbox);
        }

        private static <E extends DomainEvent> DomainEventHandler<E> handler(Class<E> type, Consumer<E> callback) {
            return new DomainEventHandler<>() {
                public Class<E> eventType() { return type; }
                public void handle(E event) { callback.accept(event); }
            };
        }

        @Bean
        @Order(300)
        DomainEventHandler<RequestSubmitted> submittedProbe(EventProbe probe) {
            return handler(RequestSubmitted.class, event -> probe.observe(Kind.SUBMITTED, event,
                    event.requestId(), null, "NOTIFY_SUBMIT", "SUBMITTED"));
        }

        @Bean
        @Order(300)
        DomainEventHandler<RequestRejected> rejectedProbe(EventProbe probe) {
            return handler(RequestRejected.class, event -> probe.observe(Kind.REJECTED, event,
                    event.requestId(), null, "NOTIFY_REJECT", "REJECTED"));
        }

        @Bean
        @Order(300)
        DomainEventHandler<DecisionDelegated> delegatedProbe(EventProbe probe) {
            return handler(DecisionDelegated.class, event -> probe.observe(Kind.DELEGATED, event,
                    event.requestId(), event.delegatedTo(), "NOTIFY_DELEGATE", "DELEGATED"));
        }
    }
}
