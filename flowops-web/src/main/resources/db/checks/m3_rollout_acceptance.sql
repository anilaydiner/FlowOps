-- Read-only, fresh-bootstrap V3 rollout facts. Run in a repeatable-read, read-only transaction.
-- Zero is the expected value for violations; STARTED_LEASES is phase-dependent.
SELECT 'SCHEMA_NOT_CURRENT' AS code, CASE WHEN EXISTS(SELECT 1 FROM flyway_schema_history WHERE version='3' AND success)
    AND NOT EXISTS(SELECT 1 FROM flyway_schema_history WHERE NOT success OR (version IS NOT NULL AND version NOT IN (SELECT n::text FROM generate_series(1,3) n))) THEN 0 ELSE 1 END::bigint AS value
UNION ALL SELECT 'INVALID_ACTION_CONFIGURATION',count(*) FROM request_types WHERE jsonb_typeof(execution_actions) IS DISTINCT FROM 'array'
UNION ALL SELECT 'CANCELLED_REQUEST_RUNNABLE_JOBS',count(*) FROM execution_jobs j JOIN requests r ON r.id=j.request_id
    WHERE r.status='CANCELLED' AND j.status IN ('PLANNED','STARTED','FAILED')
UNION ALL SELECT 'STARTED_WITH_INVALID_PARENT',count(*) FROM execution_jobs j JOIN requests r ON r.id=j.request_id
    WHERE j.status='STARTED' AND (r.status<>'APPROVED' OR r.execution_started_at IS NULL OR r.execution_planned_at IS NULL OR (r.execution_start_unknown AND r.legacy_plan_disposition_id IS NULL))
UNION ALL SELECT 'STARTED_LEASES',count(*) FROM execution_jobs WHERE status='STARTED'
UNION ALL SELECT 'EXPIRED_EXECUTION_LEASES',count(*) FROM execution_jobs WHERE status='STARTED' AND lease_expires_at<=clock_timestamp()
UNION ALL SELECT 'KNOWN_PLAN_MISSING_INPUT',count(*) FROM execution_jobs j JOIN requests r ON r.id=j.request_id
    WHERE r.status='APPROVED' AND r.execution_planned_at IS NOT NULL AND (NOT r.execution_start_unknown OR r.legacy_plan_disposition_id IS NOT NULL) AND j.status IN ('PLANNED','FAILED') AND j.input_payload IS NULL
UNION ALL SELECT 'MISSING_POST_CUTOVER_PROVIDER_CONTRACT',count(*) FROM execution_attempts a
    LEFT JOIN execution_attempt_provider_contracts c USING(claim_token)
    WHERE c.claim_token IS NULL AND a.authorized_at>=(SELECT activated_at FROM execution_provider_contract_cutover)
UNION ALL SELECT 'INVALID_STORED_PROVIDER_CONTRACT',count(*) FROM (
    SELECT provider_id,revision,contract FROM execution_attempt_provider_contracts
    UNION ALL SELECT provider_id,revision,contract FROM execution_recovery_provider_contracts
    ) c WHERE contract->>'providerId' IS DISTINCT FROM provider_id OR contract->>'revision' IS DISTINCT FROM revision
    OR contract->>'transport' IS NULL OR contract->>'transport' NOT IN ('LOCAL','NETWORK')
    OR NOT (contract ?& ARRAY['connectTimeoutMillis','readTimeoutMillis','executionTimeoutMillis','reconciliationTimeoutMillis','idempotentRetries','lookupSupported','simulated'])
    OR jsonb_typeof(contract->'idempotentRetries') IS DISTINCT FROM 'boolean'
    OR jsonb_typeof(contract->'lookupSupported') IS DISTINCT FROM 'boolean'
    OR jsonb_typeof(contract->'simulated') IS DISTINCT FROM 'boolean'
    OR CASE WHEN jsonb_typeof(contract->'executionTimeoutMillis')='number' THEN (contract->>'executionTimeoutMillis')::numeric NOT BETWEEN 1 AND 86400000 ELSE true END
    OR CASE WHEN jsonb_typeof(contract->'reconciliationTimeoutMillis')='number' THEN (contract->>'reconciliationTimeoutMillis')::numeric NOT BETWEEN 1 AND 86400000 ELSE true END
    OR CASE WHEN jsonb_typeof(contract->'connectTimeoutMillis')='number' THEN (contract->>'connectTimeoutMillis')::numeric NOT BETWEEN 0 AND 86400000 ELSE true END
    OR CASE WHEN jsonb_typeof(contract->'readTimeoutMillis')='number' THEN (contract->>'readTimeoutMillis')::numeric NOT BETWEEN 0 AND 86400000 ELSE true END
UNION ALL SELECT 'UNVERIFIED_LEGACY_PROVIDER_ATTEMPTS',count(*) FROM execution_attempts a
    LEFT JOIN execution_attempt_provider_contracts c USING(claim_token) WHERE c.claim_token IS NULL
UNION ALL SELECT 'MISSING_LATEST_ATTEMPT_OUTCOME',count(*) FROM execution_attempts a JOIN execution_jobs j ON j.claim_token=a.claim_token
    WHERE j.status<>'STARTED' AND NOT EXISTS(SELECT 1 FROM execution_attempt_events e WHERE e.claim_token=a.claim_token AND e.kind='OUTCOME')
UNION ALL SELECT 'APPROVED_WITHOUT_FROZEN_PLAN',count(*) FROM requests WHERE status='APPROVED' AND execution_plan_id IS NULL
UNION ALL SELECT 'FROZEN_PLAN_JOB_MISMATCH',count(*) FROM execution_plans p JOIN requests r ON r.id=p.request_id
    WHERE r.execution_plan_id IS DISTINCT FROM p.id OR p.expected_actions<>(SELECT count(*) FROM execution_plan_actions a WHERE a.plan_id=p.id)
    OR p.expected_actions<>(SELECT count(*) FROM execution_jobs j WHERE j.request_id=p.request_id)
    OR p.expected_actions<>(SELECT count(*) FROM execution_plan_job_bindings b JOIN execution_plan_actions a ON a.plan_id=b.plan_id AND a.action=b.action
        JOIN execution_jobs j ON j.id=b.job_id WHERE b.plan_id=p.id AND j.request_id=p.request_id AND j.action=a.action
        AND j.idempotency_key=a.idempotency_key AND j.input_payload=a.input_payload AND j.correlation_id IS NOT DISTINCT FROM a.correlation_id)
UNION ALL SELECT 'MISSING_MATERIALIZATION_RECEIPT',count(*) FROM execution_plan_actions a
    LEFT JOIN execution_plan_materializations m ON m.plan_id=a.plan_id AND m.action=a.action WHERE m.plan_id IS NULL
UNION ALL SELECT 'MATERIALIZATION_IDENTITY_MISMATCH',count(*) FROM execution_plan_materializations m
    JOIN execution_plan_job_bindings b ON b.plan_id=m.plan_id AND b.action=m.action JOIN execution_jobs j ON j.id=b.job_id
    WHERE m.job_id IS DISTINCT FROM b.job_id OR m.job_created_at IS DISTINCT FROM j.created_at
UNION ALL SELECT 'LEGACY_DISPOSITION_MISMATCH',count(*) FROM requests r JOIN legacy_plan_dispositions d ON d.command_id=r.legacy_plan_disposition_id
    LEFT JOIN execution_plans p ON p.id=r.execution_plan_id WHERE d.request_id<>r.id OR d.mode<>'ADOPT' OR d.plan_id IS DISTINCT FROM p.id OR p.approval->>'trigger' IS DISTINCT FROM 'LEGACY_PLAN_DISPOSITION'
UNION ALL SELECT 'MISSING_LEGACY_PROVIDER_BINDING',count(*) FROM execution_jobs j JOIN requests r ON r.id=j.request_id
    WHERE r.legacy_plan_disposition_id IS NOT NULL AND j.status NOT IN ('COMPLETED','CANCELLED') AND NOT EXISTS(
        SELECT 1 FROM legacy_plan_provider_bindings b WHERE b.job_id=j.id AND b.disposition_id=r.legacy_plan_disposition_id)
UNION ALL SELECT 'CLOSED_OUTCOME_UNKNOWN',count(*) FROM requests WHERE status='CLOSED' AND outcome IS NULL
UNION ALL SELECT 'REQUEST_OUTCOME_MISMATCH',count(*) FROM requests WHERE outcome IS NOT NULL AND
    ((status='CLOSED' AND outcome NOT IN ('EXECUTED','REJECTED'))
    OR (status IN ('EXECUTED','REJECTED','CANCELLED','EXPIRED') AND outcome<>status)
    OR status IN ('DRAFT','SUBMITTED','IN_REVIEW','APPROVED'))
UNION ALL SELECT 'UNCERTAIN_JOBS',count(*) FROM execution_jobs WHERE status IN ('RECONCILIATION_REQUIRED','DEAD_LETTER')
UNION ALL SELECT 'UNKNOWN_APPROVED_EXECUTION',count(*) FROM requests WHERE status='APPROVED' AND ((execution_start_unknown AND legacy_plan_disposition_id IS NULL) OR execution_planned_at IS NULL)
UNION ALL SELECT 'ORPHANED_RECOVERY_COMMANDS',count(*) FROM execution_recovery_commands c
    LEFT JOIN execution_recovery_observations o USING(command_id) WHERE o.command_id IS NULL AND c.expires_at<=clock_timestamp()
UNION ALL SELECT 'NO_GLOBAL_EXECUTION_OPERATOR',CASE WHEN EXISTS(
    SELECT u.id FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN role_permissions rp ON rp.role_id=ur.role_id
    JOIN permissions p ON p.id=rp.permission_id WHERE u.active AND p.scope_type IS NULL
    AND p.code IN ('execution:read','execution:reconcile','execution:retry') GROUP BY u.id HAVING count(DISTINCT p.code)=3) THEN 0 ELSE 1 END::bigint
ORDER BY code;
