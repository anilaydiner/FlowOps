package com.flowops.domain.exception;

/**
 * Thrown when authentication fails: unknown user, wrong password, or a
 * disabled account. Mapped to HTTP 401 by the web edge.
 *
 * <p>Lives in the domain (not the application layer) so the web edge maps
 * a domain exception — never an application-service implementation detail.</p>
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
