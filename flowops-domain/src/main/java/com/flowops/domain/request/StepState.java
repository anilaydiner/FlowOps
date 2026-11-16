package com.flowops.domain.request;

/**
 * Step instance states (PLAN §3: step_instances).
 * BLOCKED is a step-level condition — the request itself stays IN_REVIEW (PLAN §5).
 */
public enum StepState {
    PENDING,
    SATISFIED,
    SKIPPED,
    BLOCKED
}
