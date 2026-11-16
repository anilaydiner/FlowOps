package com.flowops.security;

import com.flowops.domain.identity.User;
import com.flowops.application.identity.port.AccessTokenIssuer;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService implements AccessTokenIssuer {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private static final String SUBJECT = "sub";
    private static final String USERNAME = "username";
    private static final String ROLES = "roles";

    private final SecretKey key;
    private final long expirationSeconds;
    private final Clock clock;

    public JwtService(@Value("${flowops.jwt.secret}") String secret,
                      @Value("${flowops.jwt.expiration-seconds}") long expirationSeconds, Clock clock) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        if (expirationSeconds < 1) throw new IllegalArgumentException("JWT lifetime must be positive");
        this.expirationSeconds = expirationSeconds;
        this.clock = clock;
    }

    @Override
    public AccessToken issue(User user, UUID sessionId, Instant sessionExpiresAt) {
        Instant now = clock.instant();
        Instant candidate = now.plusSeconds(expirationSeconds);
        Instant expires = candidate.isBefore(sessionExpiresAt) ? candidate : sessionExpiresAt;
        List<String> roleCodes = user.roleCodes().stream().toList();
        String value = Jwts.builder()
                .issuer("flowops")
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(user.getId()))
                .claim(USERNAME, user.getUsername())
                .claim(ROLES, roleCodes)
                .claim("sid", sessionId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expires))
                .signWith(key)
                .compact();
        return new AccessToken(value, Duration.between(now, expires).getSeconds());
    }

    /**
     * @return the parsed principal, or empty when the token is invalid/expired.
     */
    public Optional<UserPrincipal> parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).requireIssuer("flowops").clock(() -> Date.from(clock.instant())).build()
                    .parseSignedClaims(token).getPayload();
            Long id = Long.valueOf(claims.get(SUBJECT, String.class));
            String username = claims.get(USERNAME, String.class);
            @SuppressWarnings("unchecked")
            List<String> roles = claims.get(ROLES, List.class);
            Set<String> roleSet = (roles != null) ? Set.copyOf(roles) : Set.of();
            // JWT expiration is exclusive, including the exact boundary instant.
            if (claims.getExpiration() == null || !clock.instant().isBefore(claims.getExpiration().toInstant())) {
                return Optional.empty();
            }
            UUID sessionId = UUID.fromString(claims.get("sid", String.class));
            return Optional.of(new UserPrincipal(id, username, roleSet, sessionId));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            // Redaction (iteration 8): never log the token itself — only why it
            // was rejected. Tokens are bearer credentials; echoing them (even
            // truncated) into logs is a leak.
            log.warn("Token rejected ({})", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public long getExpirationSeconds() {
        return expirationSeconds;
    }
}
