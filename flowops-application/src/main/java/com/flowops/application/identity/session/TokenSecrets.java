package com.flowops.application.identity.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class TokenSecrets {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TokenSecrets() {
    }

    public static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String secret) {
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public static boolean matches(String fingerprint, String passwordHash) {
        return MessageDigest.isEqual(fingerprint.getBytes(StandardCharsets.US_ASCII),
                hash(passwordHash).getBytes(StandardCharsets.US_ASCII));
    }

}
