-- V5: Scoped authorization — add scope_type/scope_value to permissions,
-- seed request-scoped permissions, create new roles (FINANCE_MANAGER, JUNIOR_APPROVER)

-- ── Add scope columns ──────────────────────────────────────────────────
ALTER TABLE permissions ADD COLUMN IF NOT EXISTS scope_type  VARCHAR(32);
ALTER TABLE permissions ADD COLUMN IF NOT EXISTS scope_value VARCHAR(128);

-- Drop the single-column unique on code; replace with composite unique.
-- NULL scope_type/scope_value means "global" (no scope constraint).
ALTER TABLE permissions DROP CONSTRAINT IF EXISTS permissions_code_key;
CREATE UNIQUE INDEX IF NOT EXISTS permissions_code_scope_ux
    ON permissions (code, COALESCE(scope_type, ''), COALESCE(scope_value, ''));

-- ── Seed request-scoped permissions ───────────────────────────────────
-- Global (unscoped) permissions
INSERT INTO permissions (code, name, description, scope_type, scope_value) VALUES
    ('request:create',      'Create Request',       'Create a new request',            NULL, NULL),
    ('request:read',        'Read Request',         'Read any request',                NULL, NULL),
    ('request:approve',     'Approve Request',      'Approve a request (any scope)',   NULL, NULL),
    ('request:reject',      'Reject Request',       'Reject a request (any scope)',    NULL, NULL),
    ('request:close',       'Close Request',        'Close a request (any scope)',     NULL, NULL),
    ('request:override:sod','Override SoD',         'Override separation-of-duties check', NULL, NULL);

-- SELF-scoped: only on the user's own requests
INSERT INTO permissions (code, name, description, scope_type, scope_value) VALUES
    ('request:read:own',    'Read Own Request',     'Read only own requests',          'SELF', NULL),
    ('request:close:own',   'Close Own Request',    'Close only own requests',         'SELF', NULL);

-- DEPARTMENT-scoped: only on requests in the user's department
INSERT INTO permissions (code, name, description, scope_type, scope_value) VALUES
    ('request:approve:dept','Approve Dept Request', 'Approve requests in own dept',    'DEPARTMENT', NULL),
    ('request:reject:dept', 'Reject Dept Request',  'Reject requests in own dept',     'DEPARTMENT', NULL);

-- THRESHOLD-scoped: only on requests up to the amount
INSERT INTO permissions (code, name, description, scope_type, scope_value) VALUES
    ('request:approve:threshold', 'Approve Up To',  'Approve requests up to amount',   'THRESHOLD', '5000');

-- ── New roles ─────────────────────────────────────────────────────────
INSERT INTO roles (code, name, description) VALUES
    ('FINANCE_MANAGER', 'Finance Manager', 'Approve/reject requests in Finance department'),
    ('JUNIOR_APPROVER', 'Junior Approver', 'Approve requests up to a threshold amount');

-- ── FINANCE_MANAGER → dept-scoped approve/reject ─────────────────────
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.code IN ('request:approve:dept', 'request:reject:dept')
WHERE r.code = 'FINANCE_MANAGER';

-- ── JUNIOR_APPROVER → threshold-scoped approve ───────────────────────
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.code = 'request:approve:threshold'
WHERE r.code = 'JUNIOR_APPROVER';

-- ── ADMIN → all new permissions (except SoD override — explicit opt-in) ──
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN'
  AND p.code <> 'request:override:sod'
  AND (r.id, p.id) NOT IN (SELECT role_id, permission_id FROM role_permissions);

-- ── MEMBER → create + own read/close ────────────────────────────────
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.code IN ('request:create', 'request:read:own', 'request:close:own')
WHERE r.code = 'MEMBER';
