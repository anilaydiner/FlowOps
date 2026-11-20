package com.flowops.domain.request;

/**
 * Request lifecycle states (PLAN §5).
 */
public enum RequestStatus {

    DRAFT, SUBMITTED, IN_REVIEW, APPROVED, EXECUTED, CLOSED, REJECTED, CANCELLED, EXPIRED;

    /** Terminal states: no further transitions allowed. */
    public boolean isTerminal() {
        return this == CLOSED || this == CANCELLED || this == EXPIRED;
    }

    public boolean acceptsReviewDecisions() {
        return this == IN_REVIEW;
    }

    public boolean canClose() {
        return this == EXECUTED || this == REJECTED;
    }

    /** A business outcome has been reached; closure may still be available. */
    public boolean isFinalized() {
        return canClose() || isTerminal();
    }

    public boolean canCancel() {
        return this == DRAFT || this == SUBMITTED || this == IN_REVIEW || this == APPROVED;
    }

}
