package com.flowops.domain.event;

/** Published after the approved request is saved, within the decision transaction. */
public record RequestApproved(Long requestId, String approverUsername) implements DomainEvent {
}
