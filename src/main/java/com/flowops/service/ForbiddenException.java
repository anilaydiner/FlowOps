package com.flowops.service;

/**
 * Mapped to 403 Forbidden — the caller is authenticated but
 * not allowed to perform the operation.
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
