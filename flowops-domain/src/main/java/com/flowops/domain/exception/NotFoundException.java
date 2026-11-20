package com.flowops.domain.exception;

/**
 * Thrown when a required entity (user, request, ...) does not exist or is not visible to
 * the caller. Mapped to HTTP 404.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

}
