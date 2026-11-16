-- V14: Delegation visibility — scoped approver roles can see and open the
-- requests they are eligible to act on.
--
-- Background: the approver queue (GET /requests/queue) is gated on the
-- `request:queue` permission, and the request detail (GET /requests/{id}) on
-- `request:read`. FINANCE_MANAGER and JUNIOR_APPROVER hold only
-- scope-limited approve/reject codes, so even when a decision was delegated
-- to them they could neither see the request in their queue nor open its
-- detail. An approver must be able to read the request it may act on, and
-- must be able to list the queue where delegated decisions surface.
--
-- Idempotent: safe to re-run (NOT EXISTS guard).

-- ── FINANCE_MANAGER → queue visibility + request read ──────────────────
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'request:queue',
    'request:read'
)
WHERE r.code = 'FINANCE_MANAGER'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- ── JUNIOR_APPROVER → queue visibility + request read ──────────────────
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'request:queue',
    'request:read'
)
WHERE r.code = 'JUNIOR_APPROVER'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
