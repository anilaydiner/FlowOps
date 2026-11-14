package com.flowops.security;

import com.flowops.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private static final String SUBJECT = "sub";
    private static final String USERNAME = "username";
    private static final String ROLES = "roles";

    private final SecretKey key;
    private final long expirationSeconds;

    public JwtService(@Value("${flowops.jwt.secret}") String secret,
                      @Value("${flowops.jwt.expiration-seconds}") long expirationSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationSeconds = expirationSeconds;
    }

    public String issue(User user) {
        Date now = new Date();
        List<String> roleCodes = user.roleCodes().stream().toList();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim(USERNAME, user.getUsername())
                .claim(ROLES, roleCodes)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationSeconds * 1000L))
                .signWith(key)
                .compact();
    }

    /**
     * @return the parsed principal, or empty when the token is invalid/expired.
     */
    public Optional<UserPrincipal> parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            Long id = Long.valueOf(claims.get(SUBJECT, String.class));
            String username = claims.get(USERNAME, String.class);
            @SuppressWarnings("unchecked")
            List<String> roles = claims.get(ROLES, List.class);
            Set<String> roleSet = (roles != null) ? Set.copyOf(roles) : Set.of();
            return Optional.of(new UserPrincipal(id, username, roleSet));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            // Redaction (iteration 8): never log the token itself — only why it
            // was rejected. Tokens are bearer credentials; echoing them (even
            // truncated) into logs is a leak.
            log.warn("Token rejected: {} ({} chars, not logged)", e.getMessage(),
                    token != null ? token.length() : 0);
            return Optional.empty();
        }
    }

    public long getExpirationSeconds() {
        return expirationSeconds;
    }
}
