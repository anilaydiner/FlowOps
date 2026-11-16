-- V6: Admin management permissions (coarse controller guard)
-- PLAN §7: "Enforcement points: controller guard (coarse permission) + engine guard (fine scope/eligibility)."

INSERT INTO permissions (code, name, description, scope_type, scope_value) VALUES
    ('admin:users',        'Manage Users',        'Create, read, update, delete users',              NULL, NULL),
    ('admin:roles',        'Manage Roles',        'Create, update, delete roles',                    NULL, NULL),
    ('admin:request-types','Manage Request Types','Create, update request types',                    NULL, NULL),
    ('admin:workflows',    'Manage Workflows',    'Create, update, delete workflows',                NULL, NULL),
    ('admin:actuator',     'Access Actuator',     'Access actuator endpoints (health, metrics, etc)', NULL, NULL);

-- ADMIN gets all admin management permissions (only if not already assigned)
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'admin:users', 'admin:roles', 'admin:request-types', 'admin:workflows', 'admin:actuator'
)
WHERE r.code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
