package com.flowops;

import com.flowops.application.event.DomainEventHandler;
import com.flowops.application.event.SynchronousDomainEventPublisher;
import com.flowops.domain.event.DomainEvent;
import com.flowops.domain.request.event.RequestApproved;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DomainEventPublisherTest {
    private DomainEventHandler<RequestApproved> handler(Consumer<RequestApproved> operation) {
        return new DomainEventHandler<>() {
            public Class<RequestApproved> eventType() { return RequestApproved.class; }
            public void handle(RequestApproved event) { operation.accept(event); }
        };
    }

    @Test
    void fansOutInRegistrationOrderOnCallerThreadAndIgnoresOtherEventTypes() {
        List<String> calls = new ArrayList<>();
        Thread caller = Thread.currentThread();
        RequestApproved event = new RequestApproved(12L,java.util.UUID.randomUUID(),com.flowops.domain.request.ApprovalEvidence.human(1L,"approver",1L,1L));
        record OtherEvent() implements DomainEvent {}
        DomainEventHandler<OtherEvent> unrelated = new DomainEventHandler<>() {
            public Class<OtherEvent> eventType() { return OtherEvent.class; }
            public void handle(OtherEvent ignored) { fail("Wrong event type dispatched"); }
        };
        var publisher = new SynchronousDomainEventPublisher(List.of(
                handler(received -> {
                    assertSame(event, received);
                    assertSame(caller, Thread.currentThread());
                    calls.add("notify");
                }), unrelated, handler(received -> calls.add("enqueue"))));
        publisher.publish(event);
        assertEquals(List.of("notify", "enqueue"), calls);
    }

    @Test
    void propagatesHandlerFailureAndStopsDispatch() {
        List<String> calls = new ArrayList<>();
        RuntimeException failure = new IllegalStateException("listener failed");
        var publisher = new SynchronousDomainEventPublisher(List.of(
                handler(event -> calls.add("first")),
                handler(event -> { throw failure; }),
                handler(event -> calls.add("last"))));
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> publisher.publish(new RequestApproved(12L,java.util.UUID.randomUUID(),com.flowops.domain.request.ApprovalEvidence.human(1L,"approver",1L,1L)))));
        assertEquals(List.of("first"), calls);
    }

    @Test
    void missingHandlersAndNullEventsFailExplicitly() {
        var publisher = new SynchronousDomainEventPublisher(List.of());
        assertThrows(IllegalStateException.class,
                () -> publisher.publish(new RequestApproved(12L,java.util.UUID.randomUUID(),com.flowops.domain.request.ApprovalEvidence.human(1L,"approver",1L,1L))));
        assertThrows(NullPointerException.class, () -> publisher.publish(null));
    }
}
