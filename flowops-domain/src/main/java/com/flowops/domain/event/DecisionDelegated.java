package com.flowops.domain.event;

/** A decision was delegated without advancing its workflow step. */
public record DecisionDelegated(Long requestId, String delegatorUsername, Long delegatedTo, String stepCode) implements DomainEvent {
}
