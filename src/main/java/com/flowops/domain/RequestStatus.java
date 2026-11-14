package com.flowops.domain;

/**
 * Request lifecycle states (PLAN §5).
 */
public enum RequestStatus {
    DRAFT,
    SUBMITTED,
    IN_REVIEW,
    APPROVED,
    EXECUTED,
    CLOSED,
    REJECTED,
    CANCELLED,
    EXPIRED;

    /** Terminal states: no further transitions allowed. */
    public boolean isTerminal() {
        return this == CLOSED || this == CANCELLED || this == REJECTED || this == EXPIRED;
    }
}
