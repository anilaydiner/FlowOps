package com.flowops.domain.execution;

/**
 * Lifecycle states for an execution job (PLAN §8, iteration 7). PLANNED → STARTED →
 * COMPLETED / FAILED → DEAD_LETTER
 */
public enum JobStatus {

    PLANNED, STARTED, COMPLETED, FAILED, CANCELLED, RECONCILIATION_REQUIRED, DEAD_LETTER;

    public boolean isTerminal() {
        return this == COMPLETED || this == DEAD_LETTER || this == CANCELLED || this == RECONCILIATION_REQUIRED;
    }

}
