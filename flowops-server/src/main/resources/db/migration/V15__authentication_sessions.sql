CREATE TABLE authentication_sessions (
    id                      UUID PRIMARY KEY,
    user_id                 BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    credential_fingerprint  CHAR(64) NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL,
    expires_at              TIMESTAMPTZ NOT NULL,
    revoked_at              TIMESTAMPTZ,
    CHECK (expires_at > created_at)
);
CREATE INDEX idx_authentication_sessions_user ON authentication_sessions(user_id);
CREATE INDEX idx_authentication_sessions_expiry ON authentication_sessions(expires_at);

-- Retain consumed hashes until session expiry so replay can revoke the full family.
-- Raw refresh tokens are never persisted.
CREATE TABLE authentication_refresh_tokens (
    token_hash  CHAR(64) PRIMARY KEY,
    session_id  UUID NOT NULL REFERENCES authentication_sessions(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CHECK (expires_at > created_at)
);
CREATE INDEX idx_authentication_refresh_session ON authentication_refresh_tokens(session_id);
