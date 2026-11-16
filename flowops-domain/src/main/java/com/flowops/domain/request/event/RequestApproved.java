package com.flowops.domain.request.event;

import com.flowops.domain.event.DomainEvent;

/** Published after the approved request is saved, within the decision transaction. */
public record RequestApproved(Long requestId, String approverUsername) implements DomainEvent {
}
