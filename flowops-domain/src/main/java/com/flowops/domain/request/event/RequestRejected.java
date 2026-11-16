package com.flowops.domain.request.event;

import com.flowops.domain.event.DomainEvent;

/** A workflow decision rejected the request. */
public record RequestRejected(Long requestId, String rejectorUsername, String justification) implements DomainEvent {
}
