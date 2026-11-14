-- V9: Audit immutability (PLAN §8, iteration 8)
--
-- Audit records are append-only. Enforced at the database level so that NO
-- client, admin, or application bug can rewrite or erase history:
--
--   decisions        — fully immutable: a decision is a point-in-time event;
--                      corrections are new rows, never edits.
--   execution_jobs   — identity immutable (key/request/action/created_at);
--                      lifecycle columns (status, retry_count, next_retry_at,
--                      last_error, result, completed_at) may evolve — that IS
--                      the job lifecycle. DELETE blocked.
--   clarifications   — question/asker immutable; the answer columns may be
--                      set (answer flow). DELETE blocked.

-- ── decisions: no UPDATE, no DELETE ────────────────────────────────────────
CREATE OR REPLACE FUNCTION fn_decisions_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'decisions are append-only audit records (UPDATE/DELETE blocked)';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_decisions_immutable
    BEFORE UPDATE OR DELETE ON decisions
    FOR EACH ROW EXECUTE FUNCTION fn_decisions_immutable();

-- ── execution_jobs: identity immutable, lifecycle allowed ──────────────────
CREATE OR REPLACE FUNCTION fn_execution_jobs_immutable() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'execution_jobs are append-only audit records (DELETE blocked)';
    END IF;
    IF NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
       OR NEW.request_id IS DISTINCT FROM OLD.request_id
       OR NEW.action IS DISTINCT FROM OLD.action
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'execution_jobs identity is immutable (%): %', TG_OP, COALESCE(NEW.idempotency_key, OLD.idempotency_key);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_execution_jobs_immutable
    BEFORE UPDATE OR DELETE ON execution_jobs
    FOR EACH ROW EXECUTE FUNCTION fn_execution_jobs_immutable();

-- ── clarifications: question immutable, answer flow allowed, no DELETE ─────
CREATE OR REPLACE FUNCTION fn_clarifications_immutable() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'clarifications are append-only audit records (DELETE blocked)';
    END IF;
    IF NEW.question IS DISTINCT FROM OLD.question
       OR NEW.asked_by IS DISTINCT FROM OLD.asked_by
       OR NEW.request_id IS DISTINCT FROM OLD.request_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'clarifications identity is immutable (%)', TG_OP;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_clarifications_immutable
    BEFORE UPDATE OR DELETE ON clarifications
    FOR EACH ROW EXECUTE FUNCTION fn_clarifications_immutable();
