package com.flowops.domain.request.event;

import com.flowops.domain.event.DomainEvent;

/** A decision was delegated without advancing its workflow step. */
public record DecisionDelegated(Long requestId, String delegatorUsername, Long delegatedTo, String stepCode) implements DomainEvent {
}
