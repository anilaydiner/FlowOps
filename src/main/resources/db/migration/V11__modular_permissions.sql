-- V11: Modularized permissions — action-per-permission
-- Every controller endpoint maps to exactly one permission code.
-- ADMIN is not a hardcoded bypass; it holds the codes it needs.
-- Idempotent: safe to re-run (ON CONFLICT DO NOTHING via NOT EXISTS).

-- ── Users ──────────────────────────────────────────────────────────────
INSERT INTO permissions (code, name, description, scope_type, scope_value)
SELECT v.code, v.name, v.description, v.scope_type, v.scope_value
FROM (VALUES
    ('users:list',       'List Users',        'List and filter users',            NULL, NULL),
    ('users:create',     'Create User',       'Create a new user account',        NULL, NULL),
    ('users:update',     'Update User',       'Update an existing user account',  NULL, NULL),
    ('users:delete',     'Delete User',       'Delete a user account',            NULL, NULL),
    ('users:read:self',  'Read Own Profile',  'View own user profile',            'SELF', NULL)
) AS v(code, name, description, scope_type, scope_value)
WHERE NOT EXISTS (
    SELECT 1 FROM permissions p
    WHERE p.code = v.code
      AND COALESCE(p.scope_type, '')  = COALESCE(v.scope_type, '')
      AND COALESCE(p.scope_value, '') = COALESCE(v.scope_value, '')
);

-- ── Roles ──────────────────────────────────────────────────────────────
INSERT INTO permissions (code, name, description, scope_type, scope_value)
SELECT v.code, v.name, v.description, v.scope_type, v.scope_value
FROM (VALUES
    ('roles:list',   'List Roles',   'List and filter roles',           NULL, NULL),
    ('roles:create', 'Create Role',  'Create a new role',               NULL, NULL),
    ('roles:update', 'Update Role',  'Update an existing role',         NULL, NULL),
    ('roles:delete', 'Delete Role',  'Delete a role',                   NULL, NULL)
) AS v(code, name, description, scope_type, scope_value)
WHERE NOT EXISTS (
    SELECT 1 FROM permissions p
    WHERE p.code = v.code
      AND COALESCE(p.scope_type, '')  = COALESCE(v.scope_type, '')
      AND COALESCE(p.scope_value, '') = COALESCE(v.scope_value, '')
);

-- ── Permissions ────────────────────────────────────────────────────────
INSERT INTO permissions (code, name, description, scope_type, scope_value)
SELECT v.code, v.name, v.description, v.scope_type, v.scope_value
FROM (VALUES
    ('permissions:read',  'Read Permission',  'View a specific permission',    NULL, NULL),
    ('permissions:list',  'List Permissions', 'List and filter permissions',   NULL, NULL)
) AS v(code, name, description, scope_type, scope_value)
WHERE NOT EXISTS (
    SELECT 1 FROM permissions p
    WHERE p.code = v.code
      AND COALESCE(p.scope_type, '')  = COALESCE(v.scope_type, '')
      AND COALESCE(p.scope_value, '') = COALESCE(v.scope_value, '')
);

-- ── Departments ────────────────────────────────────────────────────────
INSERT INTO permissions (code, name, description, scope_type, scope_value)
SELECT v.code, v.name, v.description, v.scope_type, v.scope_value
FROM (VALUES
    ('departments:list', 'List Departments', 'List and filter departments', NULL, NULL)
) AS v(code, name, description, scope_type, scope_value)
WHERE NOT EXISTS (
    SELECT 1 FROM permissions p
    WHERE p.code = v.code
      AND COALESCE(p.scope_type, '')  = COALESCE(v.scope_type, '')
      AND COALESCE(p.scope_value, '') = COALESCE(v.scope_value, '')
);

-- ── Request Types ──────────────────────────────────────────────────────
INSERT INTO permissions (code, name, description, scope_type, scope_value)
SELECT v.code, v.name, v.description, v.scope_type, v.scope_value
FROM (VALUES
    ('request-types:read',   'Read Request Type',   'View a specific request type',    NULL, NULL),
    ('request-types:list',   'List Request Types',  'List and filter request types',   NULL, NULL),
    ('request-types:create', 'Create Request Type', 'Create a new request type',       NULL, NULL),
    ('request-types:update', 'Update Request Type', 'Update an existing request type', NULL, NULL)
) AS v(code, name, description, scope_type, scope_value)
WHERE NOT EXISTS (
    SELECT 1 FROM permissions p
    WHERE p.code = v.code
      AND COALESCE(p.scope_type, '')  = COALESCE(v.scope_type, '')
      AND COALESCE(p.scope_value, '') = COALESCE(v.scope_value, '')
);

-- ── Workflows ──────────────────────────────────────────────────────────
INSERT INTO permissions (code, name, description, scope_type, scope_value)
SELECT v.code, v.name, v.description, v.scope_type, v.scope_value
FROM (VALUES
    ('workflows:read',   'Read Workflow',   'View a specific workflow',       NULL, NULL),
    ('workflows:list',   'List Workflows',  'List and filter workflows',      NULL, NULL),
    ('workflows:create', 'Create Workflow', 'Create a new workflow',          NULL, NULL),
    ('workflows:update', 'Update Workflow', 'Update an existing workflow',    NULL, NULL)
) AS v(code, name, description, scope_type, scope_value)
WHERE NOT EXISTS (
    SELECT 1 FROM permissions p
    WHERE p.code = v.code
      AND COALESCE(p.scope_type, '')  = COALESCE(v.scope_type, '')
      AND COALESCE(p.scope_value, '') = COALESCE(v.scope_value, '')
);

-- ── Requests (granular lifecycle) ──────────────────────────────────────
INSERT INTO permissions (code, name, description, scope_type, scope_value)
SELECT v.code, v.name, v.description, v.scope_type, v.scope_value
FROM (VALUES
    ('request:list',         'List Requests',        'List and filter requests',         NULL,   NULL),
    ('request:list:own',     'List Own Requests',    'List only own requests',           'SELF', NULL),
    ('request:update',       'Update Request',       'Update any request draft',         NULL,   NULL),
    ('request:update:own',   'Update Own Request',   'Update own request draft',         'SELF', NULL),
    ('request:cancel',       'Cancel Request',       'Cancel any request',               NULL,   NULL),
    ('request:cancel:own',   'Cancel Own Request',   'Cancel own request',               'SELF', NULL),
    ('request:submit',       'Submit Request',       'Submit any draft into workflow',   NULL,   NULL),
    ('request:submit:own',   'Submit Own Request',   'Submit own draft into workflow',   'SELF', NULL),
    ('request:queue',        'Approval Queue',       'View pending approvals inbox',     NULL,   NULL)
) AS v(code, name, description, scope_type, scope_value)
WHERE NOT EXISTS (
    SELECT 1 FROM permissions p
    WHERE p.code = v.code
      AND COALESCE(p.scope_type, '')  = COALESCE(v.scope_type, '')
      AND COALESCE(p.scope_value, '') = COALESCE(v.scope_value, '')
);

-- ── Clarifications ─────────────────────────────────────────────────────
INSERT INTO permissions (code, name, description, scope_type, scope_value)
SELECT v.code, v.name, v.description, v.scope_type, v.scope_value
FROM (VALUES
    ('clarifications:ask',         'Ask Clarification',      'Ask on any request',              NULL,   NULL),
    ('clarifications:ask:own',     'Ask Own Clarification',  'Ask on own request',              'SELF', NULL),
    ('clarifications:answer',      'Answer Clarification',   'Answer on any request',           NULL,   NULL),
    ('clarifications:answer:own',  'Answer Own Clarification','Answer on own request',          'SELF', NULL),
    ('clarifications:read',        'Read Clarifications',    'View clarifications on any request', NULL, NULL),
    ('clarifications:read:own',    'Read Own Clarifications','View clarifications on own request', 'SELF', NULL)
) AS v(code, name, description, scope_type, scope_value)
WHERE NOT EXISTS (
    SELECT 1 FROM permissions p
    WHERE p.code = v.code
      AND COALESCE(p.scope_type, '')  = COALESCE(v.scope_type, '')
      AND COALESCE(p.scope_value, '') = COALESCE(v.scope_value, '')
);

-- ── Audit (scoped variant) ─────────────────────────────────────────────
INSERT INTO permissions (code, name, description, scope_type, scope_value)
SELECT v.code, v.name, v.description, v.scope_type, v.scope_value
FROM (VALUES
    ('audit:read', 'Read Audit (Scoped)', 'Access audit timeline for own department', 'DEPARTMENT', NULL)
) AS v(code, name, description, scope_type, scope_value)
WHERE NOT EXISTS (
    SELECT 1 FROM permissions p
    WHERE p.code = v.code
      AND COALESCE(p.scope_type, '')  = COALESCE(v.scope_type, '')
      AND COALESCE(p.scope_value, '') = COALESCE(v.scope_value, '')
);

-- ── Notifications ──────────────────────────────────────────────────────
INSERT INTO permissions (code, name, description, scope_type, scope_value)
SELECT v.code, v.name, v.description, v.scope_type, v.scope_value
FROM (VALUES
    ('notifications:read',   'Read Notifications',   'View own notifications',       NULL, NULL),
    ('notifications:update', 'Update Notifications', 'Mark notifications as read',   NULL, NULL)
) AS v(code, name, description, scope_type, scope_value)
WHERE NOT EXISTS (
    SELECT 1 FROM permissions p
    WHERE p.code = v.code
      AND COALESCE(p.scope_type, '')  = COALESCE(v.scope_type, '')
      AND COALESCE(p.scope_value, '') = COALESCE(v.scope_value, '')
);

-- ═══════════════════════════════════════════════════════════════════════
-- Role assignments
-- ═══════════════════════════════════════════════════════════════════════

-- ── ADMIN → ALL new permissions ────────────────────────────────────────
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'users:list', 'users:create', 'users:update', 'users:delete', 'users:read:self',
    'roles:list', 'roles:create', 'roles:update', 'roles:delete',
    'permissions:read', 'permissions:list',
    'departments:list',
    'request-types:read', 'request-types:list', 'request-types:create', 'request-types:update',
    'workflows:read', 'workflows:list', 'workflows:create', 'workflows:update',
    'request:list', 'request:list:own', 'request:update', 'request:update:own',
    'request:cancel', 'request:cancel:own', 'request:submit', 'request:submit:own',
    'request:queue',
    'clarifications:ask', 'clarifications:ask:own',
    'clarifications:answer', 'clarifications:answer:own',
    'clarifications:read', 'clarifications:read:own',
    'audit:read',
    'notifications:read', 'notifications:update'
)
WHERE r.code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- ── MEMBER → standard user permissions ─────────────────────────────────
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    -- Self-service
    'users:read:self',
    -- Read-only reference data
    'departments:read', 'departments:list',
    'roles:read', 'roles:list',
    'permissions:read', 'permissions:list',
    'request-types:read', 'request-types:list',
    'workflows:read', 'workflows:list',
    -- Request lifecycle (own only)
    'request:create',
    'request:read:own', 'request:list:own',
    'request:update:own', 'request:cancel:own', 'request:submit:own',
    'request:close:own',
    'request:queue',
    -- Clarifications (own only)
    'clarifications:ask:own', 'clarifications:answer:own', 'clarifications:read:own',
    -- Notifications
    'notifications:read', 'notifications:update',
    -- Audit (scoped)
    'audit:read'
)
WHERE r.code = 'MEMBER'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
