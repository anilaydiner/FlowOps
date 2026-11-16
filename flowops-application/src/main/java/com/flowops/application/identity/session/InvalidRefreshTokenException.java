package com.flowops.application.identity.session;

/** A uniform authentication failure; never carries the submitted credential. */
public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException() { super("Invalid or expired refresh token"); }
}
