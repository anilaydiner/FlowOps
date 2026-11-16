package com.flowops.domain.event;

/** A draft has entered its workflow; the saved state may already be approved when all steps are skipped. */
public record RequestSubmitted(Long requestId) implements DomainEvent {
}
