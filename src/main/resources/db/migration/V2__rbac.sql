-- V2: RBAC — replace users.role with roles + permissions catalog

-- ── Roles ──────────────────────────────────────────────────────────────
CREATE TABLE roles (
    id          BIGSERIAL    PRIMARY KEY,
    code        VARCHAR(32)  NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ── Permissions ────────────────────────────────────────────────────────
CREATE TABLE permissions (
    id          BIGSERIAL    PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ── Junction: user ↔ role ─────────────────────────────────────────────
CREATE TABLE user_roles (
    user_id BIGINT NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES roles(id)   ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

-- ── Junction: role ↔ permission ───────────────────────────────────────
CREATE TABLE role_permissions (
    role_id       BIGINT NOT NULL REFERENCES roles(id)       ON DELETE CASCADE,
    permission_id BIGINT NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

-- ── Seed roles ─────────────────────────────────────────────────────────
INSERT INTO roles (code, name, description) VALUES
    ('ADMIN',  'Administrator', 'Full system access'),
    ('MEMBER', 'Member',        'Standard user access');

-- ── Seed permissions (initial catalog) ─────────────────────────────────
INSERT INTO permissions (code, name, description) VALUES
    ('users:read',       'Read Users',        'View user accounts'),
    ('users:write',      'Write Users',       'Create, update, delete user accounts'),
    ('users:read:own',   'Read Own Profile',  'View own profile'),
    ('departments:read', 'Read Departments',  'View department list'),
    ('roles:read',       'Read Roles',        'View roles and their permissions'),
    ('roles:write',      'Write Roles',       'Manage roles and permission assignments');

-- ── ADMIN → all permissions ────────────────────────────────────────────
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN';

-- ── MEMBER → basic permissions ─────────────────────────────────────────
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
    'users:read:own', 'departments:read'
) WHERE r.code = 'MEMBER';

-- ── Migrate existing users to the roles table ──────────────────────────
INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u JOIN roles r ON r.code = u.role;

-- ── Drop the old column ────────────────────────────────────────────────
ALTER TABLE users DROP COLUMN role;

-- ── Indexes ────────────────────────────────────────────────────────────
CREATE INDEX idx_user_roles_role_id    ON user_roles(role_id);
CREATE INDEX idx_role_permissions_perm ON role_permissions(permission_id);
