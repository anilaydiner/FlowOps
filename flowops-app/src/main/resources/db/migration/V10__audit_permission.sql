-- V10: audit:read:any permission + AUDITOR role (Scenario 5, PLAN §9)
-- "A user without audit:read:any cannot access audit logs."

-- ── Permission ──────────────────────────────────────────────────────────
INSERT INTO permissions (code, name, description, scope_type, scope_value) VALUES
    ('audit:read:any', 'Read Audit Logs', 'Access the audit timeline for any request', NULL, NULL);

-- ── Role ────────────────────────────────────────────────────────────────
INSERT INTO roles (code, name, description) VALUES
    ('AUDITOR', 'Auditor', 'Read-only access to all audit trails');

-- ── AUDITOR → audit:read:any ───────────────────────────────────────────
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.code = 'audit:read:any'
WHERE r.code = 'AUDITOR';

-- ── ADMIN → audit:read:any (ADMIN gets all permissions) ────────────────
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.code = 'audit:read:any'
WHERE r.code = 'ADMIN';
