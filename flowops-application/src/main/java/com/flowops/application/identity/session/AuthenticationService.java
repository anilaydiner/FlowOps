package com.flowops.application.identity.session;

import com.flowops.application.identity.UserService;
import com.flowops.application.identity.port.AccessTokenIssuer;
import com.flowops.application.identity.port.AuthenticationSessionRepository;
import com.flowops.domain.identity.User;
import com.flowops.domain.identity.port.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationService {
    private final UserService users;
    private final UserRepository userRepository;
    private final AuthenticationSessionRepository sessions;
    private final AccessTokenIssuer accessTokens;
    private final SessionSettings settings;
    private final Clock clock;

    public AuthenticationService(UserService users, UserRepository userRepository,
                                 AuthenticationSessionRepository sessions, AccessTokenIssuer accessTokens,
                                 SessionSettings settings, Clock clock) {
        this.users = users;
        this.userRepository = userRepository;
        this.sessions = sessions;
        this.accessTokens = accessTokens;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional
    public TokenPair login(String username, String password) {
        User user = users.authenticate(username, password);
        Instant now = clock.instant();
        Instant deadline = now.plus(settings.maximumLifetime());
        UUID id = UUID.randomUUID();
        sessions.create(id, user.getId(), TokenSecrets.hash(user.getPasswordHash()), now, deadline);
        return issue(user, id, now, deadline);
    }

    // Reuse detection revokes the session before returning 401. That revocation must commit.
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public TokenPair refresh(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) throw new InvalidRefreshTokenException();
        String hash = TokenSecrets.hash(rawToken);
        var grant = sessions.lockByRefreshToken(hash).orElseThrow(InvalidRefreshTokenException::new);
        Instant now = clock.instant();
        if (grant.revokedAt() != null || !now.isBefore(grant.sessionExpiresAt())) {
            throw new InvalidRefreshTokenException();
        }
        if (grant.consumedAt() != null) {
            sessions.revoke(grant.sessionId(), now);
            throw new InvalidRefreshTokenException();
        }
        if (!now.isBefore(grant.tokenExpiresAt())) throw new InvalidRefreshTokenException();
        User user = userRepository.findById(grant.userId()).orElseThrow(InvalidRefreshTokenException::new);
        if (!user.isActive() || !TokenSecrets.matches(grant.credentialFingerprint(), user.getPasswordHash())) {
            sessions.revoke(grant.sessionId(), now);
            throw new InvalidRefreshTokenException();
        }
        sessions.consume(hash, now);
        return issue(user, grant.sessionId(), now, grant.sessionExpiresAt());
    }

    @Transactional
    public void logout(UUID authenticatedSessionId, String rawRefreshToken) {
        Instant now = clock.instant();
        if (authenticatedSessionId != null) {
            sessions.revoke(authenticatedSessionId, now);
            return;
        }
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            sessions.lockByRefreshToken(TokenSecrets.hash(rawRefreshToken))
                    .ifPresent(grant -> sessions.revoke(grant.sessionId(), now));
        }
    }

    @Transactional
    public void logoutAll(Long userId) {
        sessions.revokeAll(userId, clock.instant());
    }

    private TokenPair issue(User user, UUID sessionId, Instant now, Instant deadline) {
        Instant candidate = now.plus(settings.refreshLifetime());
        Instant refreshDeadline = candidate.isBefore(deadline) ? candidate : deadline;
        String raw = TokenSecrets.randomToken();
        sessions.addRefreshToken(sessionId, TokenSecrets.hash(raw), now, refreshDeadline);
        var access = accessTokens.issue(user, sessionId, deadline);
        return new TokenPair(access.value(), access.expiresIn(), raw,
                java.time.Duration.between(now, refreshDeadline).getSeconds(), deadline);
    }
}
