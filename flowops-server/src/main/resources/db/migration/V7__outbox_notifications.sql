-- V7: Transactional Outbox + Notifications feed
-- PLAN §8: "Outbox writer: in the same transaction as business state change."
-- Dispatcher polls with FOR UPDATE SKIP LOCKED, delivers email (simulated) + feed.

-- ── Outbox messages ─────────────────────────────────────────────────────
CREATE TABLE outbox (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Unique idempotency key: e.g. "req:42:submit", "req:42:approve"
    idempotency_key VARCHAR(256) NOT NULL UNIQUE,
    -- Message type: EMAIL, FEED, SLA_ALERT, etc.
    channel         VARCHAR(32)  NOT NULL,
    -- Recipient user ID (for FEED and EMAIL)
    recipient_id    BIGINT       NOT NULL,
    -- Structured payload: {requestId, type, title, body, ...}
    payload         JSONB        NOT NULL DEFAULT '{}',
    -- PENDING → SENT / FAILED → DEAD_LETTER
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    retry_count     INT          NOT NULL DEFAULT 0,
    max_retries     INT          NOT NULL DEFAULT 5,
    next_retry_at   TIMESTAMPTZ,
    last_error      TEXT,
    -- Correlation ID from the originating request
    correlation_id  VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ
);

CREATE INDEX idx_outbox_status_retry ON outbox (status, next_retry_at)
    WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX idx_outbox_recipient ON outbox (recipient_id);

-- ── Notifications feed (internal, user-visible) ──────────────────────────
CREATE TABLE notifications (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recipient_id BIGINT      NOT NULL,
    -- NOTIFY_SUBMIT, NOTIFY_APPROVE, NOTIFY_REJECT, SLA_BREACH, etc.
    type         VARCHAR(32) NOT NULL,
    title        VARCHAR(256) NOT NULL,
    body         TEXT,
    -- Reference to the related request (optional)
    request_id   BIGINT,
    read_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_notifications_recipient ON notifications (recipient_id, created_at DESC);
CREATE INDEX idx_notifications_unread ON notifications (recipient_id, created_at DESC) WHERE read_at IS NULL;
CREATE INDEX idx_notifications_request ON notifications (request_id);

-- ── Simulated email store (for testing/verification) ─────────────────────
CREATE TABLE email_log (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    to_address   VARCHAR(256) NOT NULL,
    subject      VARCHAR(256) NOT NULL,
    body         TEXT         NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'DELIVERED',
    -- Links back to outbox message
    outbox_id    BIGINT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_email_log_outbox ON email_log (outbox_id);
CREATE INDEX idx_email_log_recipient ON email_log (to_address);
