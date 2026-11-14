package com.flowops.service;

/**
 * Thrown when a resource exists but the operation conflicts with its
 * current state (e.g. duplicate username, self-deletion). Mapped to HTTP 409.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
