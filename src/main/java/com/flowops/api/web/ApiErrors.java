package com.flowops.api.web;

import java.net.URI;

/**
 * RFC 7807 error namespace for FlowOps API responses.
 *
 * <p>Every ProblemDetail body carries a stable {@code type} URI of the form
 * {@code urn:flowops:error:<name>}. This class is the single owner of that
 * namespace — web exception handlers and the security filter chain both
 * reference it instead of hardcoding URIs.</p>
 */
public final class ApiErrors {

    public static final String BASE = "urn:flowops:error:";

    private ApiErrors() {}

    /** Stable type URI for a named error (e.g. {@code not-found}, {@code validation}). */
    public static URI type(String name) {
        return URI.create(BASE + name);
    }
}
