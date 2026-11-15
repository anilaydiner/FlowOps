package com.flowops.domain.exception;

import java.util.List;

/**
 * Mapped to 403 Forbidden — the caller is authenticated but
 * not allowed to perform the operation.
 *
 * <p>Carries an optional <b>evidence</b> trail (which permission was
 * checked, which role was consulted, why it was denied) that the API
 * layer exposes in the RFC 7807 ProblemDetail response.
 */
public class ForbiddenException extends RuntimeException {

    private final List<String> evidence;

    public ForbiddenException(String message) {
        this(message, List.of());
    }

    public ForbiddenException(String message, List<String> evidence) {
        super(message);
        this.evidence = List.copyOf(evidence);
    }

    /** Explainable audit trail: which permission, role, scope was checked. */
    public List<String> getEvidence() {
        return evidence;
    }
}
