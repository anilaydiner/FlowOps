package com.flowops.domain.request.event;

import com.flowops.domain.event.DomainEvent;

/** A draft has entered its workflow; the saved state may already be approved when all steps are skipped. */
public record RequestSubmitted(Long requestId) implements DomainEvent {
}
