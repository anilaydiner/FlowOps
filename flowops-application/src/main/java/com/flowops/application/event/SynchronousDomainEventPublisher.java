package com.flowops.application.event;

import com.flowops.domain.event.DomainEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explicit synchronous fan-out, independent of Spring's configurable event multicaster.
 */
@Component
public class SynchronousDomainEventPublisher implements DomainEventPublisher {

    private final Map<Class<?>, List<DomainEventHandler<?>>> handlers;

    public SynchronousDomainEventPublisher(List<DomainEventHandler<?>> handlers) {
        Map<Class<?>, List<DomainEventHandler<?>>> registry = new HashMap<>();
        // Spring supplies its ordered handler beans, including their proxies.
        for (DomainEventHandler<?> handler : handlers) {
            Class<?> type = Objects.requireNonNull(handler.eventType(), "eventType");
            registry.computeIfAbsent(type, ignored -> new ArrayList<>()).add(handler);
        }
        registry.replaceAll((type, subscribers) -> List.copyOf(subscribers));
        this.handlers = Map.copyOf(registry);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(DomainEvent event) {
        Objects.requireNonNull(event, "event");
        List<DomainEventHandler<?>> subscribers = handlers.get(event.getClass());
        if (subscribers == null) {
            throw new IllegalStateException("No handlers for " + event.getClass().getName());
        }
        for (DomainEventHandler<?> handler : subscribers) {
            dispatch(handler, event);
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends DomainEvent> void dispatch(DomainEventHandler<?> handler, E event) {
        // Registration binds E to Class<E>; dispatch uses exact-class lookup.
        ((DomainEventHandler<E>) handler).handle(event);
    }

}
