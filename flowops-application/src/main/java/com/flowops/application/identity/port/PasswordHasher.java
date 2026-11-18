package com.flowops.application.identity.port;

/** Hash and verify credentials using the configured password hashing implementation. */
public interface PasswordHasher {
    String hash(String password);
    boolean matches(String password, String hash);
}
