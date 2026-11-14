-- V8: Execution Jobs + Clarifications (PLAN §8, iteration 7)
-- Execution jobs: idempotent side-effect actions triggered on APPROVED.
-- Job worker claims with FOR UPDATE SKIP LOCKED; all-jobs-done → EXECUTED.
-- Clarifications: lightweight Q&A thread on a request.
-- DELEGATE: decisions.delegated_to records who an approver delegated to.

-- ── Execution actions on request types ─────────────────────────────────
ALTER TABLE request_types
    ADD COLUMN IF NOT EXISTS execution_actions JSONB NOT NULL DEFAULT '[]';

-- Seed execution actions per type (PLAN §8: provisioning / purchase ticket / accounting export)
UPDATE request_types SET execution_actions = '["ACCOUNTING_EXPORT"]' WHERE code = 'EXPENSE';
UPDATE request_types SET execution_actions = '["PROVISIONING"]'      WHERE code = 'IT_ACCESS';
UPDATE request_types SET execution_actions = '["PURCHASE_TICKET"]'   WHERE code = 'EQUIPMENT';
-- POLICY_EXCEPTION: no execution actions (empty array)

-- ── Execution jobs ──────────────────────────────────────────────────────
CREATE TABLE execution_jobs (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Unique idempotency key: "req:{id}:{ACTION}"
    idempotency_key VARCHAR(256) NOT NULL UNIQUE,
    request_id      BIGINT       NOT NULL REFERENCES requests(id) ON DELETE CASCADE,
    -- PROVISIONING | PURCHASE_TICKET | ACCOUNTING_EXPORT
    action          VARCHAR(64)  NOT NULL,
    -- PLANNED → STARTED → COMPLETED / FAILED → DEAD_LETTER
    status          VARCHAR(16)  NOT NULL DEFAULT 'PLANNED',
    retry_count     INT          NOT NULL DEFAULT 0,
    max_retries     INT          NOT NULL DEFAULT 5,
    next_retry_at   TIMESTAMPTZ,
    last_error      TEXT,
    -- Result payload from the action handler (JSONB)
    result          JSONB,
    correlation_id  VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ
);

CREATE INDEX idx_exec_jobs_status_retry ON execution_jobs (status, next_retry_at)
    WHERE status IN ('PLANNED', 'FAILED');
CREATE INDEX idx_exec_jobs_request ON execution_jobs (request_id);

-- ── Clarifications ─────────────────────────────────────────────────────
CREATE TABLE clarifications (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id   BIGINT      NOT NULL REFERENCES requests(id) ON DELETE CASCADE,
    asked_by     BIGINT      NOT NULL REFERENCES users(id),
    question     TEXT        NOT NULL,
    answer       TEXT,
    answered_by  BIGINT      REFERENCES users(id),
    answered_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_clarifications_request ON clarifications (request_id, created_at DESC);

-- ── DELEGATE support on decisions ──────────────────────────────────────
ALTER TABLE decisions
    ADD COLUMN IF NOT EXISTS delegated_to BIGINT REFERENCES users(id);

CREATE INDEX idx_decisions_delegated ON decisions (delegated_to) WHERE delegated_to IS NOT NULL;
