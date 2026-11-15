-- V12: Per-request-type dynamic permissions
--
-- Seeds REQUEST_TYPE-scoped permissions so that a role can be granted
-- the ability to create / approve / reject requests for *specific*
-- request types only, without holding the global (unscoped) permission.
--
-- The same permission code appears multiple times with different
-- scope_value entries (unique on code + scope_type + scope_value).
--
-- The AuthorizationEvaluator now tries ALL matching candidates and
-- accepts the first one whose scope check passes, so a user with
-- multiple type-scoped permissions can create any of those types.

-- ── Per-type request:create ────────────────────────────────────────────
INSERT INTO permissions (code, name, description, scope_type, scope_value)
SELECT 'request:create', 'Create ' || t.code || ' Request',
       'Create a ' || t.name || ' request (type-scoped)',
       'REQUEST_TYPE', t.code
FROM request_types t
WHERE NOT EXISTS (
    SELECT 1 FROM permissions p
    WHERE p.code = 'request:create'
      AND p.scope_type = 'REQUEST_TYPE'
      AND p.scope_value = t.code
);

-- ── Per-type request:approve ───────────────────────────────────────────
INSERT INTO permissions (code, name, description, scope_type, scope_value)
SELECT 'request:approve', 'Approve ' || t.code,
       'Approve a ' || t.name || ' request (type-scoped)',
       'REQUEST_TYPE', t.code
FROM request_types t
WHERE NOT EXISTS (
    SELECT 1 FROM permissions p
    WHERE p.code = 'request:approve'
      AND p.scope_type = 'REQUEST_TYPE'
      AND p.scope_value = t.code
);

-- ── Per-type request:reject ────────────────────────────────────────────
INSERT INTO permissions (code, name, description, scope_type, scope_value)
SELECT 'request:reject', 'Reject ' || t.code,
       'Reject a ' || t.name || ' request (type-scoped)',
       'REQUEST_TYPE', t.code
FROM request_types t
WHERE NOT EXISTS (
    SELECT 1 FROM permissions p
    WHERE p.code = 'request:reject'
      AND p.scope_type = 'REQUEST_TYPE'
      AND p.scope_value = t.code
);

-- ── Demo role: EXPENSE_CREATOR ─────────────────────────────────────────
-- Can create only EXPENSE requests, plus the self-scoped lifecycle
-- permissions to manage their own drafts/submissions.
INSERT INTO roles (code, name, description)
SELECT 'EXPENSE_CREATOR', 'Expense Creator',
       'Can create EXPENSE requests only (type-scoped)'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE code = 'EXPENSE_CREATOR');

-- EXPENSE_CREATOR → EXPENSE-scoped create
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r JOIN permissions p
    ON p.code = 'request:create'
   AND p.scope_type = 'REQUEST_TYPE'
   AND p.scope_value = 'EXPENSE'
WHERE r.code = 'EXPENSE_CREATOR'
  AND (r.id, p.id) NOT IN (SELECT role_id, permission_id FROM role_permissions);

-- EXPENSE_CREATOR → self-scoped lifecycle + basic reads
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r JOIN permissions p
    ON p.code IN (
        'request:read:own',
        'request:list:own',
        'request:update:own',
        'request:cancel:own',
        'request:submit:own',
        'request:close:own',
        'users:read:self',
        'departments:read',
        'departments:list',
        'request-types:read',
        'request-types:list',
        'notifications:read',
        'notifications:update'
    )
WHERE r.code = 'EXPENSE_CREATOR'
  AND (r.id, p.id) NOT IN (SELECT role_id, permission_id FROM role_permissions);

-- ── ADMIN → all new type-scoped permissions ───────────────────────────
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r JOIN permissions p
    ON p.scope_type = 'REQUEST_TYPE'
WHERE r.code = 'ADMIN'
  AND (r.id, p.id) NOT IN (SELECT role_id, permission_id FROM role_permissions);
