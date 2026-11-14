-- V4: Workflows + workflow engine (iteration 4)

-- ── Workflows ────────────────────────────────────────────────────
CREATE TABLE workflows (
    id           BIGSERIAL    PRIMARY KEY,
    code         VARCHAR(64)  NOT NULL UNIQUE,
    name         VARCHAR(100) NOT NULL,
    description  TEXT,
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE workflow_steps (
    id                 BIGSERIAL    PRIMARY KEY,
    workflow_id        BIGINT       NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    position           INT          NOT NULL,
    code               VARCHAR(64)  NOT NULL,
    name               VARCHAR(100) NOT NULL,
    required_role      VARCHAR(64)  NOT NULL,
    completion_rule    VARCHAR(8)   NOT NULL DEFAULT 'ANY_OF',  -- ANY_OF | ALL_OF | K_OF_N
    required_approvals INT          NOT NULL DEFAULT 1,
    -- JSONB condition evaluated against requests.payload at materialization time,
    -- e.g. {"field":"amount","op":">","value":5000}
    condition          JSONB,
    sla_minutes        INT,
    escalation_role    VARCHAR(64),
    is_terminal        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (workflow_id, position)
);

CREATE INDEX idx_workflow_steps_wf ON workflow_steps(workflow_id);

ALTER TABLE request_types
    ADD CONSTRAINT fk_request_types_default_workflow
        FOREIGN KEY (default_workflow_id) REFERENCES workflows(id);

-- ── Step instances (materialized per request) ────────────────────
CREATE TABLE step_instances (
    id                 BIGSERIAL   PRIMARY KEY,
    request_id         BIGINT      NOT NULL REFERENCES requests(id) ON DELETE CASCADE,
    step_id            BIGINT      NOT NULL REFERENCES workflow_steps(id),
    position           INT         NOT NULL,
    state              VARCHAR(12) NOT NULL DEFAULT 'PENDING',   -- PENDING|SATISFIED|SKIPPED|BLOCKED
    rule               VARCHAR(8)  NOT NULL,
    required_approvals INT         NOT NULL,
    condition          JSONB,
    skip_reason        VARCHAR(200),
    blocked_reason     VARCHAR(200),
    satisfied_by       BIGINT      REFERENCES users(id),
    satisfied_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_step_instances_request ON step_instances(request_id);
CREATE INDEX idx_step_instances_state   ON step_instances(request_id, state);

-- ── Decisions (append-only) ──────────────────────────────────────
CREATE TABLE decisions (
    id               BIGSERIAL    PRIMARY KEY,
    request_id       BIGINT       NOT NULL REFERENCES requests(id) ON DELETE CASCADE,
    step_instance_id BIGINT       NOT NULL REFERENCES step_instances(id),
    actor_id         BIGINT       NOT NULL REFERENCES users(id),
    actor_username   VARCHAR(100),                                -- denormalized for display
    action           VARCHAR(12)  NOT NULL,                      -- APPROVE|REJECT|DELEGATE|OVERRIDE
    justification    VARCHAR(1000),
    evidence         JSONB,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_decisions_request ON decisions(request_id);
CREATE INDEX idx_decisions_step    ON decisions(step_instance_id);

-- ── Seed workflows (one per request type) ────────────────────────
INSERT INTO workflows (code, name, description) VALUES
    ('WF_EXPENSE',          'Expense Approval',          'Manager approval; Finance review required when amount > 5000'),
    ('WF_IT_ACCESS',        'IT Access Approval',        'IT approval for time-bound access'),
    ('WF_EQUIPMENT',        'Equipment Approval',        'Procurement approval for purchases'),
    ('WF_POLICY_EXCEPTION', 'Policy Exception Approval', 'Compliance approval for policy exceptions');

INSERT INTO workflow_steps (workflow_id, position, code, name, required_role, completion_rule, required_approvals, condition) VALUES
    ((SELECT id FROM workflows WHERE code = 'WF_EXPENSE'),          1, 'MANAGER_APPROVAL',     'Manager Approval',     'ADMIN', 'ANY_OF', 1, NULL),
    ((SELECT id FROM workflows WHERE code = 'WF_EXPENSE'),          2, 'FINANCE_REVIEW',       'Finance Review',       'ADMIN', 'ANY_OF', 1, '{"field":"amount","op":">","value":5000}'::jsonb),
    ((SELECT id FROM workflows WHERE code = 'WF_IT_ACCESS'),        1, 'IT_APPROVAL',          'IT Approval',          'ADMIN', 'ANY_OF', 1, NULL),
    ((SELECT id FROM workflows WHERE code = 'WF_EQUIPMENT'),        1, 'PROCUREMENT_APPROVAL', 'Procurement Approval', 'ADMIN', 'ANY_OF', 1, NULL),
    ((SELECT id FROM workflows WHERE code = 'WF_POLICY_EXCEPTION'), 1, 'COMPLIANCE_APPROVAL',  'Compliance Approval',  'ADMIN', 'ANY_OF', 1, NULL);

-- Link each request type to its workflow (code convention: WF_ + type code)
UPDATE request_types
   SET default_workflow_id = (SELECT id FROM workflows WHERE code = 'WF_' || request_types.code)
 WHERE code IN ('EXPENSE', 'IT_ACCESS', 'EQUIPMENT', 'POLICY_EXCEPTION');
