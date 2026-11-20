package com.flowops.domain.exception;

/**
 * Thrown when a resource exists but the operation conflicts with its current state (e.g.
 * duplicate username, self-deletion, editing a non-draft request). Mapped to HTTP 409.
 */
public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String message) {
        this("conflict", message);
    }

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

}
