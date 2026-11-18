-- Supplemental read-only M1/M2/M4 checks, only AFTER bootstrap schema V3 is installed.
-- Run alongside m3_rollout_acceptance.sql in a repeatable-read, read-only transaction.
-- BLOCKER must be zero. REVIEW needs an explicit disposition/owner, not necessarily zero.
-- These aggregates do not evaluate current permission scopes or prove provider outcomes.
SELECT 'ACTIVE_STEP_CARDINALITY' AS code,'BLOCKER' AS severity,count(*)::bigint AS value
FROM requests r WHERE r.status='IN_REVIEW' AND
    (SELECT count(*) FROM step_instances s WHERE s.request_id=r.id AND s.state IN ('PENDING','BLOCKED'))<>1
UNION ALL SELECT 'ACTIVE_CHILD_ON_STOPPED_REQUEST','BLOCKER',count(*)
FROM step_instances s JOIN requests r ON r.id=s.request_id WHERE s.state IN ('PENDING','BLOCKED') AND r.status NOT IN ('SUBMITTED','IN_REVIEW')
UNION ALL SELECT 'VOTE_SOURCE_MISMATCH','BLOCKER',count(*)
FROM step_approval_votes v LEFT JOIN decisions d ON d.id=v.decision_id
WHERE d.id IS NULL OR d.step_instance_id<>v.step_instance_id OR d.actor_id<>v.actor_id OR d.action<>'APPROVE' OR d.created_at<>v.created_at
UNION ALL SELECT 'MISSING_EFFECTIVE_VOTE','BLOCKER',count(*)
FROM (SELECT DISTINCT step_instance_id,actor_id FROM decisions WHERE action='APPROVE') d
WHERE NOT EXISTS(SELECT 1 FROM step_approval_votes v WHERE v.step_instance_id=d.step_instance_id AND v.actor_id=d.actor_id)
UNION ALL SELECT 'INITIAL_ROSTER_COUNT_MISMATCH','BLOCKER',count(*) FROM step_instances s
WHERE s.roster_snapshotted AND s.state<>'SKIPPED' AND (s.roster_size IS NULL OR s.roster_size<>(
    SELECT count(*) FROM step_participants p WHERE p.step_instance_id=s.id AND p.predecessor_id IS NULL AND p.recovery_id IS NULL))
UNION ALL SELECT 'LEGACY_DUPLICATE_APPROVAL_HISTORY','REVIEW',count(*) FROM legacy_duplicate_approval_votes
UNION ALL SELECT 'SATISFIED_WITH_INSUFFICIENT_DISTINCT_VOTES','REVIEW',count(*) FROM step_instances s
WHERE s.state='SATISFIED' AND (SELECT count(*) FROM step_approval_votes v WHERE v.step_instance_id=s.id)<s.required_approvals
UNION ALL SELECT 'ACTIVE_LEGACY_ROSTERS','REVIEW',count(*) FROM step_instances s JOIN requests r ON r.id=s.request_id
WHERE r.status='IN_REVIEW' AND s.state IN ('PENDING','BLOCKED') AND NOT s.roster_snapshotted
UNION ALL SELECT 'BLOCKED_OWNER_UNAVAILABLE','REVIEW',count(*) FROM step_instances s JOIN requests r ON r.id=s.request_id
LEFT JOIN users u ON u.id=s.recovery_owner_id WHERE r.status='IN_REVIEW' AND s.state='BLOCKED'
AND (s.recovery_owner_unavailable OR u.id IS NULL OR NOT u.active)
UNION ALL SELECT 'BLOCKED_REQUESTS','REVIEW',count(*) FROM step_instances s JOIN requests r ON r.id=s.request_id WHERE r.status='IN_REVIEW' AND s.state='BLOCKED'
UNION ALL SELECT 'IDENTITY_RECONCILIATION_BACKLOG','REVIEW',count(*) FROM eligibility_reconciliation_queue
UNION ALL SELECT 'OVERDUE_REVIEW_WORK','REVIEW',count(*) FROM step_instances s JOIN requests r ON r.id=s.request_id
WHERE r.status='IN_REVIEW' AND s.state IN ('PENDING','BLOCKED') AND s.review_due_at<=CURRENT_TIMESTAMP
UNION ALL SELECT 'DUE_BUSINESS_EXPIRY','REVIEW',count(*) FROM step_instances s JOIN requests r ON r.id=s.request_id
WHERE r.status='IN_REVIEW' AND s.state IN ('PENDING','BLOCKED') AND s.expires_at<=CURRENT_TIMESTAMP
UNION ALL SELECT 'OUTBOX_DEAD_LETTERS','REVIEW',count(*) FROM outbox WHERE status='DEAD_LETTER'
UNION ALL SELECT 'OUTBOX_UNDELIVERED','REVIEW',count(*) FROM outbox WHERE status IN ('PENDING','FAILED')
ORDER BY code;
