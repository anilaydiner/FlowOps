package com.flowops.persistence;

import com.flowops.application.identity.port.AuthenticationSessionRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuthenticationSessionRepository implements AuthenticationSessionRepository {

    private final JdbcTemplate jdbc;

    public JdbcAuthenticationSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void create(UUID id, Long userId, String fingerprint, Instant now, Instant deadline) {
        jdbc.update(
                "INSERT INTO authentication_sessions(id,user_id,credential_fingerprint,created_at,expires_at) VALUES (?,?,?,?,?)",
                id, userId, fingerprint, Timestamp.from(now), Timestamp.from(deadline));
    }

    @Override
    public void addRefreshToken(UUID id, String hash, Instant now, Instant deadline) {
        jdbc.update(
                "INSERT INTO authentication_refresh_tokens(token_hash,session_id,created_at,expires_at) VALUES (?,?,?,?)",
                hash, id, Timestamp.from(now), Timestamp.from(deadline));
    }

    @Override
    public Optional<RefreshGrant> lockByRefreshToken(String hash) {
        var ids = jdbc.query("SELECT session_id FROM authentication_refresh_tokens WHERE token_hash=?",
                (rs, row) -> rs.getObject(1, UUID.class), hash);
        if (ids.isEmpty())
            return Optional.empty();
        // All rotation/logout operations lock sessions first; re-read token state after
        // waiting.
        jdbc.query("SELECT id FROM authentication_sessions WHERE id=? FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class), ids.getFirst());
        return jdbc
            .query("""
                    SELECT s.id,s.user_id,s.credential_fingerprint,s.expires_at AS session_expires_at,s.revoked_at,
                           t.expires_at AS token_expires_at,t.consumed_at
                    FROM authentication_sessions s JOIN authentication_refresh_tokens t ON t.session_id=s.id
                    WHERE t.token_hash=?
                    """,
                    (rs, row) -> new RefreshGrant(rs.getObject("id", UUID.class), rs.getLong("user_id"),
                            rs.getString("credential_fingerprint"), rs.getTimestamp("session_expires_at").toInstant(),
                            instant(rs.getTimestamp("revoked_at")), rs.getTimestamp("token_expires_at").toInstant(),
                            instant(rs.getTimestamp("consumed_at"))),
                    hash)
            .stream()
            .findFirst();
    }

    @Override
    public void consume(String hash, Instant now) {
        jdbc.update("UPDATE authentication_refresh_tokens SET consumed_at=? WHERE token_hash=?", Timestamp.from(now),
                hash);
    }

    @Override
    public void revoke(UUID id, Instant now) {
        jdbc.update("UPDATE authentication_sessions SET revoked_at=COALESCE(revoked_at,?) WHERE id=?",
                Timestamp.from(now), id);
    }

    @Override
    public void revokeAll(Long userId, Instant now) {
        jdbc.update(
                "UPDATE authentication_sessions SET revoked_at=COALESCE(revoked_at,?) WHERE user_id=? AND revoked_at IS NULL",
                Timestamp.from(now), userId);
    }

    @Override
    public int deleteExpired(Instant now) {
        return jdbc.update("DELETE FROM authentication_sessions WHERE expires_at<=?", Timestamp.from(now));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

}
