package com.flowops.domain.exception;

/**
 * Thrown when a resource exists but the operation conflicts with its
 * current state (e.g. duplicate username, self-deletion, editing a
 * non-draft request). Mapped to HTTP 409.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
