package com.flowops.service;

import java.util.Map;

/**
 * Thrown when a request payload fails per-type validation.
 * Carries field → message violations, rendered as a 400
 * ProblemDetail with a {@code violations} property.
 */
public class RequestValidationException extends RuntimeException {

    private final Map<String, String> violations;

    public RequestValidationException(Map<String, String> violations) {
        super("Request payload validation failed");
        this.violations = Map.copyOf(violations);
    }

    public Map<String, String> getViolations() {
        return violations;
    }
}
