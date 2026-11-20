package com.flowops.application.event;

import com.flowops.domain.event.DomainEvent;

public interface DomainEventHandler<E extends DomainEvent> {

    Class<E> eventType();

    void handle(E event);

}
