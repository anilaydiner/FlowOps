package com.flowops.application.event;

import com.flowops.domain.event.DomainEvent;

public interface DomainEventPublisher {
    /** Run all subscribers synchronously in the current transaction; failures propagate. */
    void publish(DomainEvent event);
}
