package com.flowops.domain.request;

public enum BlockReason {
    NO_ELIGIBLE_APPROVER,
    INSUFFICIENT_QUORUM,
    PARTICIPANT_UNAVAILABLE,
    /** Imported blocks predate structured eligibility evidence. */
    LEGACY_UNCLASSIFIED
}
