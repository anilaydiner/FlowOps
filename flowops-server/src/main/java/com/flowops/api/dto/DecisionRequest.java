package com.flowops.api.dto;

import com.flowops.domain.request.DecisionAction;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DecisionRequest(
        @NotNull DecisionAction action,
        @Size(max = 1000) String justification,
        /** Required when action == DELEGATE: the user ID to delegate to. */
        Long delegatedTo
) {
    /** Backward-compatible 2-arg constructor. */
    public DecisionRequest(DecisionAction action, String justification) {
        this(action, justification, null);
    }
}
