package com.flowops.domain.policy;

import java.util.List;

/**
 * The result of an authorization check (PLAN §7).
 *
 * @param allowed  whether the action is permitted
 * @param evidence explainable trace: which permission, which role,
 *                 which scope matched (or why it didn't)
 */
public record AuthorizationDecision(boolean allowed, List<String> evidence) {

    public static AuthorizationDecision allow(List<String> evidence) {
        return new AuthorizationDecision(true, List.copyOf(evidence));
    }

    public static AuthorizationDecision deny(List<String> evidence) {
        return new AuthorizationDecision(false, List.copyOf(evidence));
    }

    /** Convenience: deny with a single reason. */
    public static AuthorizationDecision deny(String reason) {
        return new AuthorizationDecision(false, List.of(reason));
    }

    /** Merge this decision's evidence into a map for JSONB storage. */
    public java.util.Map<String, Object> toEvidenceMap() {
        return java.util.Map.of(
                "allowed", allowed,
                "evidence", evidence
        );
    }
}
