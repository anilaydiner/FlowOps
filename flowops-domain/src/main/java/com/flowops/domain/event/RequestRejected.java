package com.flowops.domain.event;

/** A workflow decision rejected the request. */
public record RequestRejected(Long requestId, String rejectorUsername, String justification) implements DomainEvent {
}
