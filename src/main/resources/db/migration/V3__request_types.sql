-- V3: Request types + requests (draft lifecycle)

-- ── Additional owning departments (PDF §5.1) ──────────────────────────
INSERT INTO departments (code, name) VALUES
    ('PROCUREMENT', 'Procurement'),
    ('COMPLIANCE',  'Compliance');

-- ── Request types ──────────────────────────────────────────────────────
CREATE TABLE request_types (
    id                  BIGSERIAL   PRIMARY KEY,
    code                VARCHAR(32) NOT NULL UNIQUE,
    name                VARCHAR(100) NOT NULL,
    description         TEXT,
    owning_dept_id      BIGINT      REFERENCES departments(id),
    -- FK to workflows deferred to V4 (workflows table created in iteration 4)
    default_workflow_id BIGINT,
    required_fields     JSONB       NOT NULL DEFAULT '{}',
    validation_rules    JSONB       NOT NULL DEFAULT '{}',
    is_active           BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Requests ───────────────────────────────────────────────────────────
CREATE TABLE requests (
    id             BIGSERIAL   PRIMARY KEY,
    type_id        BIGINT      NOT NULL REFERENCES request_types(id),
    requester_id   BIGINT      NOT NULL REFERENCES users(id),
    status         VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    payload        JSONB       NOT NULL DEFAULT '{}',
    version        BIGINT      NOT NULL DEFAULT 0,
    correlation_id VARCHAR(36),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    submitted_at   TIMESTAMPTZ
);

CREATE INDEX idx_requests_type       ON requests(type_id);
CREATE INDEX idx_requests_requester  ON requests(requester_id);
CREATE INDEX idx_requests_status     ON requests(status);
CREATE INDEX idx_request_types_dept  ON request_types(owning_dept_id);

-- ── Seed the 4 request types (PDF §5.1) ───────────────────────────────
INSERT INTO request_types (code, name, description, owning_dept_id, required_fields, validation_rules) VALUES
    ('EXPENSE',
     'Expense Reimbursement',
     'Reimbursement of business expenses',
     (SELECT id FROM departments WHERE code = 'FINANCE'),
     '{"amount": "Expense amount", "description": "Reason for the expense"}'::jsonb,
     '{"amount": {"type": "number", "min": 0.01}}'::jsonb),

    ('IT_ACCESS',
     'IT Access Request',
     'Access to a system or application; always time-bound',
     (SELECT id FROM departments WHERE code = 'IT'),
     '{"resource": "System or application name", "duration": "Time-bound access period (e.g. 30d, 2w, 3m)"}'::jsonb,
     '{"duration": {"pattern": "^[0-9]+(d|days|w|weeks|m|months)$"}}'::jsonb),

    ('EQUIPMENT',
     'Equipment Purchase',
     'Purchase of hardware or equipment',
     (SELECT id FROM departments WHERE code = 'PROCUREMENT'),
     '{"item": "Equipment description", "quantity": "Number of units"}'::jsonb,
     '{"quantity": {"type": "number", "min": 1}}'::jsonb),

    ('POLICY_EXCEPTION',
     'Policy Exception',
     'Exception to an existing policy; requires justification',
     (SELECT id FROM departments WHERE code = 'COMPLIANCE'),
     '{"policy": "Policy being excepted", "justification": "Business justification"}'::jsonb,
     '{}'::jsonb);
