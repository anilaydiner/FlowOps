-- Final workflow, audit, execution and recovery integrity rules. No historical upgrade steps.

-- flowops_payload_amount(jsonb)
CREATE FUNCTION flowops_payload_amount(payload jsonb) RETURNS double precision
    LANGUAGE plpgsql IMMUTABLE
    AS $_$
DECLARE amount DOUBLE PRECISION;
BEGIN
    IF jsonb_typeof(payload->'amount') NOT IN ('number', 'string') THEN RETURN NULL; END IF;
    IF (payload->>'amount') !~ '^[[:space:]]*[+-]?([0-9]+([.][0-9]*)?|[.][0-9]+)([eE][+-]?[0-9]+)?[[:space:]]*$' THEN
        RETURN NULL;
    END IF;
    amount := (payload->>'amount')::DOUBLE PRECISION;
    IF amount NOT BETWEEN '-1.7976931348623157e308'::DOUBLE PRECISION
                      AND '1.7976931348623157e308'::DOUBLE PRECISION THEN RETURN NULL; END IF;
    RETURN amount;
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range THEN
    RETURN NULL;
END;
$_$;

-- fn_attempt_provider_contract_identity()
CREATE FUNCTION fn_attempt_provider_contract_identity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM execution_attempts a WHERE a.claim_token=NEW.claim_token AND a.action=NEW.action) THEN
        RAISE EXCEPTION 'provider contract must identify its recorded action attempt' USING ERRCODE='23514';
    END IF;
    IF EXISTS (SELECT 1 FROM execution_attempts a JOIN execution_attempts other ON other.job_id=a.job_id
        JOIN execution_attempt_provider_contracts c ON c.claim_token=other.claim_token
        WHERE a.claim_token=NEW.claim_token AND c.provider_id<>NEW.provider_id) THEN
        RAISE EXCEPTION 'logical action provider identity cannot change across attempts' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

-- fn_attempt_provider_contract_required()
CREATE FUNCTION fn_attempt_provider_contract_required() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM execution_attempt_provider_contracts WHERE claim_token=NEW.claim_token) THEN
        RAISE EXCEPTION 'new execution attempts require immutable provider timeout contracts' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

-- fn_capture_plan_materialization()
CREATE FUNCTION fn_capture_plan_materialization() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE j execution_jobs%ROWTYPE;
DECLARE receipt execution_plan_materializations%ROWTYPE;
BEGIN
    SELECT * INTO j FROM execution_jobs WHERE id=NEW.job_id;
    IF j.id IS NULL THEN
        RAISE EXCEPTION 'materialization requires the original job row' USING ERRCODE='23514';
    END IF;
    INSERT INTO execution_plan_materializations(plan_id,action,job_id,job_created_at,initial_max_retries,unstarted_at_capture,provenance)
    VALUES(NEW.plan_id,NEW.action,j.id,j.created_at,j.max_retries,
        j.status='PLANNED' AND j.started_at IS NULL AND j.attempt_count=0 AND j.retry_count=0 AND NOT j.start_inferred
        AND j.claim_token IS NULL AND j.lease_expires_at IS NULL AND j.completed_at IS NULL
        AND j.cancelled_at IS NULL AND j.cancelled_by IS NULL AND j.next_retry_at IS NULL AND j.last_error IS NULL AND j.result IS NULL,'RECORDED')
    ON CONFLICT(plan_id,action) DO NOTHING;
    SELECT * INTO receipt FROM execution_plan_materializations WHERE plan_id=NEW.plan_id AND action=NEW.action;
    IF receipt.job_id IS DISTINCT FROM j.id OR receipt.job_created_at IS DISTINCT FROM j.created_at THEN
        RAISE EXCEPTION 'materialization identity cannot be replaced or inferred' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

-- fn_clarifications_immutable()
CREATE FUNCTION fn_clarifications_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'clarifications are append-only audit records (DELETE blocked)';
    END IF;
    IF NEW.question IS DISTINCT FROM OLD.question
       OR NEW.asked_by IS DISTINCT FROM OLD.asked_by
       OR NEW.request_id IS DISTINCT FROM OLD.request_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'clarifications identity is immutable (%)', TG_OP;
    END IF;
    RETURN NEW;
END;
$$;

-- fn_decisions_immutable()
CREATE FUNCTION fn_decisions_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'decisions are append-only audit records (UPDATE/DELETE blocked)';
END;
$$;

-- fn_delegate_approval_slot()
CREATE FUNCTION fn_delegate_approval_slot() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE previous step_participants%ROWTYPE;
BEGIN
    IF (SELECT roster_snapshotted FROM step_instances WHERE id = NEW.step_instance_id) THEN
        SELECT p.* INTO previous FROM step_participants p WHERE p.step_instance_id = NEW.step_instance_id
            AND p.user_id = NEW.actor_id
            AND NOT EXISTS (SELECT 1 FROM step_participants next WHERE next.predecessor_id = p.id);
        IF previous.id IS NULL OR NEW.delegated_to IS NULL THEN
            RAISE EXCEPTION 'delegation requires a current roster slot and target' USING ERRCODE = '23514';
        END IF;
        INSERT INTO step_participants(id, slot_id, step_instance_id, user_id, predecessor_id, decision_id, created_at)
        VALUES (gen_random_uuid(), previous.slot_id, NEW.step_instance_id, NEW.delegated_to, previous.id, NEW.id, NEW.created_at);
    END IF;
    RETURN NEW;
END;
$$;

-- fn_execution_attempt_event_identity()
CREATE FUNCTION fn_execution_attempt_event_identity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.claim_token IS NOT NULL AND NOT EXISTS (SELECT 1 FROM execution_attempts a WHERE a.claim_token=NEW.claim_token
        AND a.job_id=NEW.job_id AND a.request_id=NEW.request_id) THEN
        RAISE EXCEPTION 'attempt event has mismatched identity' USING ERRCODE='23514';
    END IF;
    IF NEW.baseline_job_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM execution_attempt_baselines b
        WHERE b.job_id=NEW.baseline_job_id AND b.job_id=NEW.job_id AND b.request_id=NEW.request_id) THEN
        RAISE EXCEPTION 'attempt event has mismatched baseline' USING ERRCODE='23514';
    END IF;
    IF NEW.recovery_command_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM execution_recovery_commands c
        WHERE c.command_id=NEW.recovery_command_id AND c.job_id=NEW.job_id AND c.request_id=NEW.request_id) THEN
        RAISE EXCEPTION 'attempt event has mismatched recovery command' USING ERRCODE='23514';
    END IF;
    IF NEW.kind='HEARTBEAT' AND NOT EXISTS (SELECT 1 FROM execution_attempts a JOIN execution_jobs j ON j.id=a.job_id
        WHERE a.claim_token=NEW.claim_token AND j.status='STARTED' AND j.claim_token=a.claim_token
        AND j.attempt_count=a.attempt_number AND j.lease_expires_at=NEW.lease_expires_at
        AND NEW.lease_expires_at<=a.deadline AND NEW.occurred_at>=a.authorized_at) THEN
        RAISE EXCEPTION 'heartbeat must match the live bounded attempt lease' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

-- fn_execution_attempt_identity()
CREATE FUNCTION fn_execution_attempt_identity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM execution_jobs j WHERE j.id=NEW.job_id AND j.request_id=NEW.request_id
        AND j.status='STARTED' AND j.claim_token=NEW.claim_token AND j.attempt_count=NEW.attempt_number
        AND j.idempotency_key=NEW.idempotency_key AND j.action=NEW.action AND j.input_payload=NEW.input_payload
        AND j.lease_expires_at=NEW.initial_lease_expires_at) THEN
        RAISE EXCEPTION 'attempt must match the durably authorized job claim and input' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

-- fn_execution_attempt_registered()
CREATE FUNCTION fn_execution_attempt_registered() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    -- Deferred until commit: start/renewal state and immutable evidence are one transaction.
    IF NEW.status='STARTED' AND NOT EXISTS (SELECT 1 FROM execution_attempts a
        WHERE a.claim_token=NEW.claim_token AND a.job_id=NEW.id AND a.request_id=NEW.request_id
        AND a.attempt_number=NEW.attempt_count
        AND NEW.lease_expires_at<=a.deadline
        AND (NEW.lease_expires_at=a.initial_lease_expires_at OR EXISTS (SELECT 1 FROM execution_attempt_events e
             WHERE e.claim_token=a.claim_token AND e.kind='HEARTBEAT' AND e.lease_expires_at=NEW.lease_expires_at)))
        AND NOT EXISTS (SELECT 1 FROM execution_attempt_baselines b WHERE b.job_id=NEW.id AND b.request_id=NEW.request_id
            AND b.status='STARTED' AND b.claim_token=NEW.claim_token AND b.attempt_count=NEW.attempt_count
            AND b.observed_lease_expires_at=NEW.lease_expires_at) THEN
        RAISE EXCEPTION 'execution claim requires immutable attempt and lease evidence' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' AND OLD.status='STARTED' AND NEW.status='STARTED' AND OLD.claim_token=NEW.claim_token
       AND NEW.lease_expires_at IS DISTINCT FROM OLD.lease_expires_at THEN
        IF NEW.lease_expires_at<=OLD.lease_expires_at OR NOT EXISTS (SELECT 1 FROM execution_attempt_events e
            WHERE e.claim_token=NEW.claim_token AND e.kind='HEARTBEAT' AND e.lease_expires_at=NEW.lease_expires_at
            AND e.occurred_at<OLD.lease_expires_at) THEN
            RAISE EXCEPTION 'lease renewal requires timely immutable heartbeat evidence' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;

-- fn_execution_jobs_immutable()
CREATE FUNCTION fn_execution_jobs_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'execution_jobs are append-only audit records (DELETE blocked)';
    END IF;
    IF NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
       OR NEW.request_id IS DISTINCT FROM OLD.request_id
       OR NEW.action IS DISTINCT FROM OLD.action
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'execution_jobs identity is immutable (%): %', TG_OP, COALESCE(NEW.idempotency_key, OLD.idempotency_key);
    END IF;
    RETURN NEW;
END;
$$;

-- fn_execution_plan_action_insert()
CREATE FUNCTION fn_execution_plan_action_insert() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM execution_plans p JOIN requests r ON r.id=p.request_id
        WHERE p.id=NEW.plan_id AND r.status='APPROVED' AND r.execution_plan_id IS NULL) THEN
        RAISE EXCEPTION 'frozen plan actions can only be created during initial approval' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

-- fn_execution_plan_complete_manifest()
CREATE FUNCTION fn_execution_plan_complete_manifest() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE p execution_plans%ROWTYPE;
BEGIN
    SELECT * INTO p FROM execution_plans WHERE id=NEW.id;
    IF NOT EXISTS (SELECT 1 FROM requests r WHERE r.id=p.request_id AND r.execution_plan_id=p.id AND r.execution_planned_at=p.planned_at)
       OR (SELECT count(*) FROM execution_plan_actions WHERE plan_id=p.id)<>p.expected_actions
       OR (SELECT count(*) FROM execution_plan_job_bindings WHERE plan_id=p.id)<>p.expected_actions
       OR (SELECT count(*) FROM execution_jobs WHERE request_id=p.request_id)<>p.expected_actions THEN
        RAISE EXCEPTION 'approval plan, expected actions and materialized jobs must commit together' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

-- fn_execution_plan_job_identity()
CREATE FUNCTION fn_execution_plan_job_identity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM execution_plans p JOIN execution_plan_actions a ON a.plan_id=p.id
        JOIN execution_jobs j ON j.id=NEW.job_id WHERE p.id=NEW.plan_id AND a.action=NEW.action
        AND j.request_id=p.request_id AND j.action=a.action AND j.idempotency_key=a.idempotency_key
        AND j.input_payload=a.input_payload AND j.correlation_id IS NOT DISTINCT FROM a.correlation_id) THEN
        RAISE EXCEPTION 'job must match its immutable planned action and input' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

-- fn_execution_recovery_identity()
CREATE FUNCTION fn_execution_recovery_identity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM execution_jobs WHERE id=NEW.job_id AND request_id=NEW.request_id
        AND attempt_count=NEW.attempt_count AND claim_token IS NOT DISTINCT FROM NEW.claim_token) THEN
        RAISE EXCEPTION 'recovery command must identify the current request job attempt' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

-- fn_execution_start_guard()
CREATE FUNCTION fn_execution_start_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP='UPDATE' THEN
        IF OLD.started_at IS NOT NULL AND NEW.started_at IS DISTINCT FROM OLD.started_at THEN
            RAISE EXCEPTION 'first job start is immutable' USING ERRCODE='23514';
        END IF;
        IF NEW.claim_token IS DISTINCT FROM OLD.claim_token AND NOT
           (OLD.status IN ('PLANNED','FAILED') AND NEW.status='STARTED' AND NEW.attempt_count=OLD.attempt_count+1) THEN
            RAISE EXCEPTION 'claim ownership changes only on a new authorized attempt' USING ERRCODE='23514';
        END IF;
        IF NEW.input_payload IS DISTINCT FROM OLD.input_payload THEN
            RAISE EXCEPTION 'execution input is immutable' USING ERRCODE='23514';
        END IF;
        IF NEW.attempt_count<OLD.attempt_count OR (OLD.start_inferred AND NOT NEW.start_inferred) THEN
            RAISE EXCEPTION 'execution evidence cannot be erased' USING ERRCODE='23514';
        END IF;
        IF OLD.status='CANCELLED' AND NEW.status<>'CANCELLED' THEN
            RAISE EXCEPTION 'cancelled jobs cannot restart' USING ERRCODE='23514';
        END IF;
        IF OLD.cancelled_at IS NOT NULL AND (NEW.cancelled_at,NEW.cancelled_by) IS DISTINCT FROM (OLD.cancelled_at,OLD.cancelled_by) THEN
            RAISE EXCEPTION 'job cancellation evidence is immutable' USING ERRCODE='23514';
        END IF;
    END IF;
    IF NEW.started_at IS NOT NULL AND NOT EXISTS(SELECT 1 FROM requests r WHERE r.id=NEW.request_id AND r.execution_started_at IS NOT NULL) THEN
        RAISE EXCEPTION 'job start evidence requires the parent start latch' USING ERRCODE='23514';
    END IF;
    IF NEW.status='STARTED' AND NOT EXISTS(SELECT 1 FROM requests r WHERE r.id=NEW.request_id
        AND r.status='APPROVED' AND r.execution_planned_at IS NOT NULL AND r.execution_started_at IS NOT NULL
        AND (NOT r.execution_start_unknown OR EXISTS(SELECT 1 FROM legacy_plan_dispositions d
            WHERE d.command_id=r.legacy_plan_disposition_id AND d.request_id=r.id AND d.mode='ADOPT' AND d.plan_id=r.execution_plan_id))) THEN
        RAISE EXCEPTION 'job start requires durable parent authorization' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

-- fn_job_plan_registered()
CREATE FUNCTION fn_job_plan_registered() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE plan UUID;
BEGIN
    SELECT execution_plan_id INTO plan FROM requests WHERE id=NEW.request_id;
    IF plan IS NOT NULL AND NOT EXISTS (SELECT 1 FROM execution_plan_job_bindings b JOIN execution_plan_actions a
        ON a.plan_id=b.plan_id AND a.action=b.action WHERE b.plan_id=plan AND b.job_id=NEW.id
        AND a.action=NEW.action AND a.idempotency_key=NEW.idempotency_key AND a.input_payload=NEW.input_payload
        AND a.correlation_id IS NOT DISTINCT FROM NEW.correlation_id) THEN
        RAISE EXCEPTION 'execution job must belong to the frozen request manifest' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

-- fn_legacy_disposition_complete()
CREATE FUNCTION fn_legacy_disposition_complete() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.mode='ADOPT' AND (
        NOT EXISTS(SELECT 1 FROM requests r JOIN execution_plans p ON p.id=r.execution_plan_id
            WHERE r.id=NEW.request_id AND r.legacy_plan_disposition_id=NEW.command_id AND p.id=NEW.plan_id
                AND r.status IN ('APPROVED','EXECUTED'))
        OR EXISTS(SELECT 1 FROM execution_jobs j WHERE j.request_id=NEW.request_id AND
            (j.status NOT IN ('COMPLETED','RECONCILIATION_REQUIRED') OR (j.status<>'COMPLETED' AND NOT EXISTS(
                SELECT 1 FROM legacy_plan_provider_bindings b WHERE b.job_id=j.id AND b.disposition_id=NEW.command_id))))) THEN
        RAISE EXCEPTION 'adoption, full manifest and held provider-bound work must commit together' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

-- fn_legacy_disposition_insert()
CREATE FUNCTION fn_legacy_disposition_insert() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM requests r WHERE r.id=NEW.request_id AND r.status='APPROVED'
        AND r.execution_plan_id IS NULL AND r.legacy_plan_disposition_id IS NULL AND r.version=NEW.expected_version) THEN
        RAISE EXCEPTION 'legacy disposition requires the current unplanned approval version' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

-- fn_legacy_provider_binding()
CREATE FUNCTION fn_legacy_provider_binding() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE original_provider TEXT;
BEGIN
    IF NOT EXISTS(SELECT 1 FROM legacy_plan_dispositions d JOIN execution_jobs j ON j.request_id=d.request_id
        JOIN execution_plan_job_bindings b ON b.plan_id=d.plan_id AND b.job_id=j.id
        WHERE d.command_id=NEW.disposition_id AND d.mode='ADOPT' AND j.id=NEW.job_id) THEN
        RAISE EXCEPTION 'provider disposition must identify its adopted job' USING ERRCODE='23514';
    END IF;
    SELECT c.provider_id INTO original_provider FROM execution_attempts a JOIN execution_attempt_provider_contracts c USING(claim_token)
        WHERE a.job_id=NEW.job_id ORDER BY a.attempt_number LIMIT 1;
    IF original_provider IS NOT NULL AND original_provider<>NEW.provider_id THEN
        RAISE EXCEPTION 'provider disposition cannot change a recorded original provider' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

-- fn_legacy_request_plan()
CREATE FUNCTION fn_legacy_request_plan() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.legacy_plan_disposition_id IS NOT NULL AND NEW.legacy_plan_disposition_id IS DISTINCT FROM OLD.legacy_plan_disposition_id THEN
        RAISE EXCEPTION 'legacy plan authorization identity is immutable' USING ERRCODE='23514';
    END IF;
    IF NEW.legacy_plan_disposition_id IS DISTINCT FROM OLD.legacy_plan_disposition_id AND NOT EXISTS(
        SELECT 1 FROM legacy_plan_dispositions d JOIN execution_plans p ON p.id=d.plan_id
        WHERE d.command_id=NEW.legacy_plan_disposition_id AND d.mode='ADOPT' AND d.request_id=NEW.id
            AND d.expected_version=OLD.version AND OLD.status='APPROVED' AND NEW.status='APPROVED'
            AND OLD.execution_plan_id IS NULL AND NEW.execution_plan_id=p.id AND p.request_id=NEW.id
            AND p.approval->>'trigger'='LEGACY_PLAN_DISPOSITION' AND p.approval->>'actorKind'='UNKNOWN'
            AND p.approval->>'actorId' IS NULL AND p.approval->>'initiatedById'=d.actor_id::text) THEN
        RAISE EXCEPTION 'legacy execution authorization requires its reviewed manifest' USING ERRCODE='23514';
    END IF;
    IF NEW.execution_plan_id IS NOT NULL AND EXISTS(SELECT 1 FROM execution_plans p WHERE p.id=NEW.execution_plan_id
        AND p.approval->>'trigger'='LEGACY_PLAN_DISPOSITION') AND NEW.legacy_plan_disposition_id IS NULL THEN
        RAISE EXCEPTION 'a legacy manifest cannot bypass disposition storage' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

-- fn_lifecycle_audit_immutable()
CREATE FUNCTION fn_lifecycle_audit_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'lifecycle audit history is append-only' USING ERRCODE = '23514';
END;
$$;

-- fn_record_approval_vote()
CREATE FUNCTION fn_record_approval_vote() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE si step_instances%ROWTYPE; assigned_slot UUID;
BEGIN
    IF NEW.action = 'APPROVE' THEN
        SELECT * INTO si FROM step_instances WHERE id = NEW.step_instance_id;
        IF si.roster_snapshotted THEN
            SELECT p.slot_id INTO assigned_slot FROM step_participants p
            WHERE p.step_instance_id = si.id AND p.user_id = NEW.actor_id
              AND NOT EXISTS (SELECT 1 FROM step_participants next WHERE next.predecessor_id = p.id);
            IF assigned_slot IS NULL OR NEW.request_id <> si.request_id THEN
                RAISE EXCEPTION 'approval requires a current roster slot' USING ERRCODE = '23514';
            END IF;
        END IF;
        INSERT INTO step_approval_votes(step_instance_id, actor_id, decision_id, created_at, slot_id)
        VALUES (NEW.step_instance_id, NEW.actor_id, NEW.id, NEW.created_at, assigned_slot);
    END IF;
    RETURN NEW;
END;
$$;

-- fn_recovery_provider_contract_identity()
CREATE FUNCTION fn_recovery_provider_contract_identity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM execution_recovery_commands c JOIN execution_jobs j ON j.id=c.job_id
        WHERE c.command_id=NEW.command_id AND j.action=NEW.action) THEN
        RAISE EXCEPTION 'provider contract must identify its recorded recovery action' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

-- fn_recovery_provider_contract_required()
CREATE FUNCTION fn_recovery_provider_contract_required() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM execution_recovery_provider_contracts WHERE command_id=NEW.command_id) THEN
        RAISE EXCEPTION 'new recovery commands require immutable provider timeout contracts' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

-- fn_request_execution_latch()
CREATE FUNCTION fn_request_execution_latch() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.execution_started_at IS NOT NULL AND NEW.execution_started_at IS DISTINCT FROM OLD.execution_started_at THEN
        RAISE EXCEPTION 'execution start latch cannot be cleared or rewritten' USING ERRCODE='23514';
    END IF;
    IF OLD.execution_planned_at IS NOT NULL AND NEW.execution_planned_at IS DISTINCT FROM OLD.execution_planned_at THEN
        RAISE EXCEPTION 'execution planning timestamp is immutable' USING ERRCODE='23514';
    END IF;
    IF OLD.execution_start_inferred AND NOT NEW.execution_start_inferred THEN
        RAISE EXCEPTION 'legacy execution evidence cannot be erased' USING ERRCODE='23514';
    END IF;
    IF OLD.execution_start_unknown AND NOT NEW.execution_start_unknown AND NEW.execution_started_at IS NULL THEN
        RAISE EXCEPTION 'unknown legacy execution cannot be declared unstarted' USING ERRCODE='23514';
    END IF;
    IF NEW.status='CANCELLED' AND OLD.status<>'CANCELLED' AND OLD.status='APPROVED' AND NEW.execution_planned_at IS NULL THEN
        RAISE EXCEPTION 'cancellation requires a recorded execution plan' USING ERRCODE='23514';
    END IF;
    IF NEW.status='CANCELLED' AND OLD.status<>'CANCELLED'
       AND (NEW.execution_started_at IS NOT NULL OR NEW.execution_start_unknown) THEN
        RAISE EXCEPTION 'execution start prevents cancellation' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

-- fn_request_milestone_identity()
CREATE FUNCTION fn_request_milestone_identity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE item RECORD;
DECLARE before_value JSONB;
DECLARE after_value JSONB;
DECLARE expected TEXT;
BEGIN
    IF OLD.outcome IS NOT NULL AND NEW.outcome IS DISTINCT FROM OLD.outcome THEN
        RAISE EXCEPTION 'request business outcome is immutable' USING ERRCODE='23514';
    END IF;
    FOR item IN SELECT * FROM (VALUES
        ('submitted_at','SUBMITTED'),('approved_at','APPROVED'),('rejected_at','REJECTED'),
        ('executed_at','EXECUTED'),('cancelled_at','CANCELLED'),('expired_at','EXPIRED'),('closed_at','CLOSED')) AS milestones(column_name,target_status)
    LOOP
        before_value=to_jsonb(OLD)->item.column_name;
        after_value=to_jsonb(NEW)->item.column_name;
        IF before_value<>'null'::jsonb AND after_value IS DISTINCT FROM before_value THEN
            RAISE EXCEPTION 'request milestone % is immutable',item.column_name USING ERRCODE='23514';
        END IF;
        IF after_value IS DISTINCT FROM before_value AND after_value<>'null'::jsonb
           AND (NEW.status<>item.target_status OR NEW.status=OLD.status) THEN
            RAISE EXCEPTION 'request milestone requires its corresponding transition' USING ERRCODE='23514';
        END IF;
        IF NEW.status IS DISTINCT FROM OLD.status AND NEW.status=item.target_status AND after_value='null'::jsonb THEN
            RAISE EXCEPTION 'new request transitions require their milestone time' USING ERRCODE='23514';
        END IF;
    END LOOP;
    expected=CASE WHEN NEW.status='CLOSED' THEN
        CASE WHEN OLD.status IN ('EXECUTED','REJECTED') THEN OLD.status ELSE OLD.outcome END
        WHEN NEW.status IN ('EXECUTED','REJECTED','CANCELLED','EXPIRED') THEN NEW.status END;
    IF NEW.status IS DISTINCT FROM OLD.status AND NEW.outcome IS DISTINCT FROM expected THEN
        RAISE EXCEPTION 'request outcome must match its business transition and survive closure' USING ERRCODE='23514';
    END IF;
    IF NEW.outcome IS DISTINCT FROM OLD.outcome AND NEW.status=OLD.status THEN
        RAISE EXCEPTION 'historical outcome cannot be inferred by an ordinary update' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

-- fn_request_plan_identity()
CREATE FUNCTION fn_request_plan_identity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP='UPDATE' AND OLD.execution_plan_id IS NOT NULL AND NEW.execution_plan_id IS DISTINCT FROM OLD.execution_plan_id THEN
        RAISE EXCEPTION 'request execution plan identity is immutable' USING ERRCODE='23514';
    END IF;
    IF NEW.execution_plan_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM execution_plans p
        WHERE p.id=NEW.execution_plan_id AND p.request_id=NEW.id AND p.planned_at=NEW.execution_planned_at) THEN
        RAISE EXCEPTION 'request must reference its own frozen execution plan' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' AND NEW.status='CANCELLED' AND OLD.status='APPROVED' AND NEW.execution_plan_id IS NULL THEN
        RAISE EXCEPTION 'approved cancellation requires a known frozen manifest' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

-- fn_step_deadlines_immutable()
CREATE FUNCTION fn_step_deadlines_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF (NEW.activated_at,NEW.review_due_at,NEW.expires_at) IS DISTINCT FROM
       (OLD.activated_at,OLD.review_due_at,OLD.expires_at) THEN
        RAISE EXCEPTION 'activated step deadlines are immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

-- fn_step_escalation_policy_immutable()
CREATE FUNCTION fn_step_escalation_policy_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF (NEW.escalation_interval_minutes,NEW.max_escalation_levels) IS DISTINCT FROM
       (OLD.escalation_interval_minutes,OLD.max_escalation_levels) THEN
        RAISE EXCEPTION 'activated escalation policy is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

-- fn_step_policy_immutable()
CREATE FUNCTION fn_step_policy_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF (NEW.request_id, NEW.step_id, NEW.position, NEW.rule, NEW.required_approvals, NEW.condition,
        NEW.step_code, NEW.step_name, NEW.required_role, NEW.sla_minutes, NEW.escalation_role,
        NEW.roster_snapshotted, NEW.roster_size)
        IS DISTINCT FROM
       (OLD.request_id, OLD.step_id, OLD.position, OLD.rule, OLD.required_approvals, OLD.condition,
        OLD.step_code, OLD.step_name, OLD.required_role, OLD.sla_minutes, OLD.escalation_role,
        OLD.roster_snapshotted, OLD.roster_size) THEN
        RAISE EXCEPTION 'materialized step policy is immutable';
    END IF;
    RETURN NEW;
END;
$$;

-- fn_step_roster_complete()
CREATE FUNCTION fn_step_roster_complete() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    -- roster_size remains the immutable activation electorate size. Recovery additions
    -- are separately authorized and do not rewrite that historical snapshot or threshold.
    IF NEW.roster_snapshotted AND NEW.roster_size IS NOT NULL AND
       (SELECT count(*) FROM step_participants WHERE step_instance_id=NEW.id
           AND predecessor_id IS NULL AND recovery_id IS NULL) <> NEW.roster_size THEN
        RAISE EXCEPTION 'participant roster size does not match its snapshot' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

-- fn_validate_approval_vote_source()
CREATE FUNCTION fn_validate_approval_vote_source() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM decisions WHERE id = NEW.decision_id
            AND step_instance_id = NEW.step_instance_id AND actor_id = NEW.actor_id
            AND action = 'APPROVE' AND created_at = NEW.created_at) THEN
        RAISE EXCEPTION 'approval vote must match its source decision' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM step_instances WHERE id = NEW.step_instance_id AND roster_snapshotted) THEN
        IF NEW.slot_id IS NULL OR NOT EXISTS (SELECT 1 FROM step_participants p
            WHERE p.step_instance_id = NEW.step_instance_id AND p.slot_id = NEW.slot_id AND p.user_id = NEW.actor_id
              AND NOT EXISTS (SELECT 1 FROM step_participants next WHERE next.predecessor_id = p.id)) THEN
            RAISE EXCEPTION 'vote must belong to the assigned roster slot' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.slot_id IS NOT NULL THEN
        RAISE EXCEPTION 'legacy votes cannot claim a snapshot slot' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- fn_validate_step_participant()
CREATE FUNCTION fn_validate_step_participant() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE si step_instances%ROWTYPE; previous step_participants%ROWTYPE;
BEGIN
    SELECT * INTO si FROM step_instances WHERE id=NEW.step_instance_id FOR UPDATE;
    IF NOT si.roster_snapshotted OR si.state NOT IN ('PENDING','BLOCKED') THEN
        RAISE EXCEPTION 'step cannot accept a roster assignment' USING ERRCODE='23514';
    END IF;
    IF EXISTS (SELECT 1 FROM requests WHERE id=si.request_id AND requester_id=NEW.user_id) THEN
        RAISE EXCEPTION 'requester cannot own an approval slot' USING ERRCODE='23514';
    END IF;
    IF NEW.recovery_id IS NOT NULL THEN
        IF si.state <> 'BLOCKED' OR NOT EXISTS (SELECT 1 FROM step_recoveries r
            JOIN requests req ON req.id=si.request_id WHERE r.id=NEW.recovery_id
            AND r.step_instance_id=si.id AND r.block_episode=si.block_episode AND req.status='IN_REVIEW') THEN
            RAISE EXCEPTION 'assignment requires current blocked-step recovery' USING ERRCODE='23514';
        END IF;
        IF NEW.predecessor_id IS NULL AND si.rule='ALL_OF'
           AND (si.roster_size<>0 OR (SELECT count(*) FROM step_participants
                WHERE step_instance_id=si.id AND predecessor_id IS NULL) >= si.required_approvals) THEN
            RAISE EXCEPTION 'ALL_OF recovery must replace an existing slot' USING ERRCODE='23514';
        END IF;
    ELSIF NEW.predecessor_id IS NULL THEN
        IF (SELECT count(*) FROM step_participants WHERE step_instance_id=si.id
                AND predecessor_id IS NULL AND recovery_id IS NULL) >= si.roster_size THEN
            RAISE EXCEPTION 'initial participant roster is frozen' USING ERRCODE='23514';
        END IF;
    END IF;
    IF NEW.predecessor_id IS NOT NULL THEN
        SELECT * INTO previous FROM step_participants WHERE id=NEW.predecessor_id;
        IF previous.id IS NULL OR previous.step_instance_id<>si.id OR previous.slot_id<>NEW.slot_id
           OR EXISTS (SELECT 1 FROM step_participants WHERE predecessor_id=previous.id)
           OR EXISTS (SELECT 1 FROM step_approval_votes WHERE step_instance_id=si.id AND slot_id=previous.slot_id) THEN
            RAISE EXCEPTION 'invalid or already voted recovery/delegation slot' USING ERRCODE='23514';
        END IF;
        IF NEW.recovery_id IS NULL AND NOT EXISTS (SELECT 1 FROM decisions WHERE id=NEW.decision_id
           AND action='DELEGATE' AND step_instance_id=si.id AND request_id=si.request_id
           AND actor_id=previous.user_id AND delegated_to=NEW.user_id AND created_at=NEW.created_at) THEN
            RAISE EXCEPTION 'invalid approval slot delegation' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;


SET default_tablespace = '';

SET default_table_access_method = heap;

-- step_approval_votes trg_approval_votes_immutable
CREATE TRIGGER trg_approval_votes_immutable BEFORE DELETE OR UPDATE ON step_approval_votes FOR EACH ROW EXECUTE FUNCTION fn_decisions_immutable();

-- execution_attempt_provider_contracts trg_attempt_provider_contract_identity
CREATE TRIGGER trg_attempt_provider_contract_identity BEFORE INSERT ON execution_attempt_provider_contracts FOR EACH ROW EXECUTE FUNCTION fn_attempt_provider_contract_identity();

-- execution_attempt_provider_contracts trg_attempt_provider_contract_immutable
CREATE TRIGGER trg_attempt_provider_contract_immutable BEFORE DELETE OR UPDATE ON execution_attempt_provider_contracts FOR EACH ROW EXECUTE FUNCTION fn_decisions_immutable();

-- execution_attempt_provider_contracts trg_attempt_provider_contract_no_truncate
CREATE TRIGGER trg_attempt_provider_contract_no_truncate BEFORE TRUNCATE ON execution_attempt_provider_contracts FOR EACH STATEMENT EXECUTE FUNCTION fn_decisions_immutable();

-- execution_attempts trg_attempt_provider_contract_required
CREATE CONSTRAINT TRIGGER trg_attempt_provider_contract_required AFTER INSERT ON execution_attempts DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fn_attempt_provider_contract_required();

-- request_audit_coverage trg_audit_coverage_immutable
CREATE TRIGGER trg_audit_coverage_immutable BEFORE DELETE OR UPDATE ON request_audit_coverage FOR EACH ROW EXECUTE FUNCTION fn_lifecycle_audit_immutable();

-- request_audit_coverage trg_audit_coverage_no_truncate
CREATE TRIGGER trg_audit_coverage_no_truncate BEFORE TRUNCATE ON request_audit_coverage FOR EACH STATEMENT EXECUTE FUNCTION fn_lifecycle_audit_immutable();

-- execution_plan_job_bindings trg_capture_plan_materialization
CREATE TRIGGER trg_capture_plan_materialization AFTER INSERT ON execution_plan_job_bindings FOR EACH ROW EXECUTE FUNCTION fn_capture_plan_materialization();

-- clarifications trg_clarifications_immutable
CREATE TRIGGER trg_clarifications_immutable BEFORE DELETE OR UPDATE ON clarifications FOR EACH ROW EXECUTE FUNCTION fn_clarifications_immutable();

-- decisions trg_decisions_immutable
CREATE TRIGGER trg_decisions_immutable BEFORE DELETE OR UPDATE ON decisions FOR EACH ROW EXECUTE FUNCTION fn_decisions_immutable();

-- decisions trg_delegate_approval_slot
CREATE TRIGGER trg_delegate_approval_slot AFTER INSERT ON decisions FOR EACH ROW WHEN (((new.action)::text = 'DELEGATE'::text)) EXECUTE FUNCTION fn_delegate_approval_slot();

-- execution_attempt_baselines trg_execution_attempt_baseline_immutable
CREATE TRIGGER trg_execution_attempt_baseline_immutable BEFORE DELETE OR UPDATE ON execution_attempt_baselines FOR EACH ROW EXECUTE FUNCTION fn_decisions_immutable();

-- execution_attempt_baselines trg_execution_attempt_baseline_no_truncate
CREATE TRIGGER trg_execution_attempt_baseline_no_truncate BEFORE TRUNCATE ON execution_attempt_baselines FOR EACH STATEMENT EXECUTE FUNCTION fn_decisions_immutable();

-- execution_attempt_events trg_execution_attempt_event_identity
CREATE TRIGGER trg_execution_attempt_event_identity BEFORE INSERT ON execution_attempt_events FOR EACH ROW EXECUTE FUNCTION fn_execution_attempt_event_identity();

-- execution_attempt_events trg_execution_attempt_event_immutable
CREATE TRIGGER trg_execution_attempt_event_immutable BEFORE DELETE OR UPDATE ON execution_attempt_events FOR EACH ROW EXECUTE FUNCTION fn_decisions_immutable();

-- execution_attempt_events trg_execution_attempt_event_no_truncate
CREATE TRIGGER trg_execution_attempt_event_no_truncate BEFORE TRUNCATE ON execution_attempt_events FOR EACH STATEMENT EXECUTE FUNCTION fn_decisions_immutable();

-- execution_attempts trg_execution_attempt_identity
CREATE TRIGGER trg_execution_attempt_identity BEFORE INSERT ON execution_attempts FOR EACH ROW EXECUTE FUNCTION fn_execution_attempt_identity();

-- execution_attempts trg_execution_attempt_immutable
CREATE TRIGGER trg_execution_attempt_immutable BEFORE DELETE OR UPDATE ON execution_attempts FOR EACH ROW EXECUTE FUNCTION fn_decisions_immutable();

-- execution_attempts trg_execution_attempt_no_truncate
CREATE TRIGGER trg_execution_attempt_no_truncate BEFORE TRUNCATE ON execution_attempts FOR EACH STATEMENT EXECUTE FUNCTION fn_decisions_immutable();

-- execution_jobs trg_execution_attempt_registered
CREATE CONSTRAINT TRIGGER trg_execution_attempt_registered AFTER INSERT OR UPDATE ON execution_jobs DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fn_execution_attempt_registered();

-- execution_jobs trg_execution_jobs_immutable
CREATE TRIGGER trg_execution_jobs_immutable BEFORE DELETE OR UPDATE ON execution_jobs FOR EACH ROW EXECUTE FUNCTION fn_execution_jobs_immutable();

-- execution_plan_actions trg_execution_plan_action_immutable
CREATE TRIGGER trg_execution_plan_action_immutable BEFORE DELETE OR UPDATE ON execution_plan_actions FOR EACH ROW EXECUTE FUNCTION fn_decisions_immutable();

-- execution_plan_actions trg_execution_plan_action_insert
CREATE TRIGGER trg_execution_plan_action_insert BEFORE INSERT ON execution_plan_actions FOR EACH ROW EXECUTE FUNCTION fn_execution_plan_action_insert();

-- execution_plan_actions trg_execution_plan_action_no_truncate
CREATE TRIGGER trg_execution_plan_action_no_truncate BEFORE TRUNCATE ON execution_plan_actions FOR EACH STATEMENT EXECUTE FUNCTION fn_decisions_immutable();

-- execution_plan_job_bindings trg_execution_plan_binding_immutable
CREATE TRIGGER trg_execution_plan_binding_immutable BEFORE DELETE OR UPDATE ON execution_plan_job_bindings FOR EACH ROW EXECUTE FUNCTION fn_decisions_immutable();

-- execution_plan_job_bindings trg_execution_plan_binding_no_truncate
CREATE TRIGGER trg_execution_plan_binding_no_truncate BEFORE TRUNCATE ON execution_plan_job_bindings FOR EACH STATEMENT EXECUTE FUNCTION fn_decisions_immutable();

-- execution_plans trg_execution_plan_complete_manifest
CREATE CONSTRAINT TRIGGER trg_execution_plan_complete_manifest AFTER INSERT ON execution_plans DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fn_execution_plan_complete_manifest();

-- execution_plans trg_execution_plan_immutable
CREATE TRIGGER trg_execution_plan_immutable BEFORE DELETE OR UPDATE ON execution_plans FOR EACH ROW EXECUTE FUNCTION fn_decisions_immutable();

-- execution_plan_job_bindings trg_execution_plan_job_identity
CREATE TRIGGER trg_execution_plan_job_identity BEFORE INSERT ON execution_plan_job_bindings FOR EACH ROW EXECUTE FUNCTION fn_execution_plan_job_identity();

-- execution_plans trg_execution_plan_no_truncate
CREATE TRIGGER trg_execution_plan_no_truncate BEFORE TRUNCATE ON execution_plans FOR EACH STATEMENT EXECUTE FUNCTION fn_decisions_immutable();

-- execution_recovery_commands trg_execution_recovery_command_immutable
CREATE TRIGGER trg_execution_recovery_command_immutable BEFORE DELETE OR UPDATE ON execution_recovery_commands FOR EACH ROW EXECUTE FUNCTION fn_decisions_immutable();

-- execution_recovery_commands trg_execution_recovery_command_no_truncate
CREATE TRIGGER trg_execution_recovery_command_no_truncate BEFORE TRUNCATE ON execution_recovery_commands FOR EACH STATEMENT EXECUTE FUNCTION fn_decisions_immutable();

-- execution_recovery_commands trg_execution_recovery_identity
CREATE TRIGGER trg_execution_recovery_identity BEFORE INSERT ON execution_recovery_commands FOR EACH ROW EXECUTE FUNCTION fn_execution_recovery_identity();

-- execution_recovery_observations trg_execution_recovery_observation_immutable
CREATE TRIGGER trg_execution_recovery_observation_immutable BEFORE DELETE OR UPDATE ON execution_recovery_observations FOR EACH ROW EXECUTE FUNCTION fn_decisions_immutable();

-- execution_recovery_observations trg_execution_recovery_observation_no_truncate
CREATE TRIGGER trg_execution_recovery_observation_no_truncate BEFORE TRUNCATE ON execution_recovery_observations FOR EACH STATEMENT EXECUTE FUNCTION fn_decisions_immutable();

-- execution_jobs trg_execution_start_guard
CREATE TRIGGER trg_execution_start_guard BEFORE INSERT OR UPDATE ON execution_jobs FOR EACH ROW EXECUTE FUNCTION fn_execution_start_guard();

-- execution_jobs trg_job_plan_registered
CREATE CONSTRAINT TRIGGER trg_job_plan_registered AFTER INSERT OR UPDATE ON execution_jobs DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fn_job_plan_registered();

-- legacy_all_of_definitions trg_legacy_all_of_definitions_immutable
CREATE TRIGGER trg_legacy_all_of_definitions_immutable BEFORE DELETE OR UPDATE ON legacy_all_of_definitions FOR EACH ROW EXECUTE FUNCTION fn_decisions_immutable();

-- legacy_plan_dispositions trg_legacy_disposition_complete
CREATE CONSTRAINT TRIGGER trg_legacy_disposition_complete AFTER INSERT ON legacy_plan_dispositions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fn_legacy_disposition_complete();

-- legacy_plan_dispositions trg_legacy_disposition_immutable
CREATE TRIGGER trg_legacy_disposition_immutable BEFORE DELETE OR UPDATE ON legacy_plan_dispositions FOR EACH ROW EXECUTE FUNCTION fn_decisions_immutable();

-- legacy_plan_dispositions trg_legacy_disposition_insert
CREATE TRIGGER trg_legacy_disposition_insert BEFORE INSERT ON legacy_plan_dispositions FOR EACH ROW EXECUTE FUNCTION fn_legacy_disposition_insert();

-- legacy_plan_dispositions trg_legacy_disposition_no_truncate
CREATE TRIGGER trg_legacy_disposition_no_truncate BEFORE TRUNCATE ON legacy_plan_dispositions FOR EACH STATEMENT EXECUTE FUNCTION fn_decisions_immutable();

-- legacy_execution_cutover trg_legacy_execution_cutover_immutable
CREATE TRIGGER trg_legacy_execution_cutover_immutable BEFORE DELETE OR UPDATE ON legacy_execution_cutover FOR EACH ROW EXECUTE FUNCTION fn_decisions_immutable();

-- legacy_execution_cutover trg_legacy_execution_cutover_no_truncate
CREATE TRIGGER trg_legacy_execution_cutover_no_truncate BEFORE TRUNCATE ON legacy_execution_cutover FOR EACH STATEMENT EXECUTE FUNCTION fn_decisions_immutable();

-- legacy_plan_provider_bindings trg_legacy_provider_binding
CREATE TRIGGER trg_legacy_provider_binding BEFORE INSERT ON legacy_plan_provider_bindings FOR EACH ROW EXECUTE FUNCTION fn_legacy_provider_binding();

-- legacy_plan_provider_bindings trg_legacy_provider_immutable
CREATE TRIGGER trg_legacy_provider_immutable BEFORE DELETE OR UPDATE ON legacy_plan_provider_bindings FOR EACH ROW EXECUTE FUNCTION fn_decisions_immutable();

-- legacy_plan_provider_bindings trg_legacy_provider_no_truncate
CREATE TRIGGER trg_legacy_provider_no_truncate BEFORE TRUNCATE ON legacy_plan_provider_bindings FOR EACH STATEMENT EXECUTE FUNCTION fn_decisions_immutable();

-- requests trg_legacy_request_plan
CREATE TRIGGER trg_legacy_request_plan BEFORE UPDATE ON requests FOR EACH ROW EXECUTE FUNCTION fn_legacy_request_plan();

-- lifecycle_audit_events trg_lifecycle_audit_immutable
CREATE TRIGGER trg_lifecycle_audit_immutable BEFORE DELETE OR UPDATE ON lifecycle_audit_events FOR EACH ROW EXECUTE FUNCTION fn_lifecycle_audit_immutable();

-- lifecycle_audit_events trg_lifecycle_audit_no_truncate
CREATE TRIGGER trg_lifecycle_audit_no_truncate BEFORE TRUNCATE ON lifecycle_audit_events FOR EACH STATEMENT EXECUTE FUNCTION fn_lifecycle_audit_immutable();

-- execution_plan_materializations trg_plan_materialization_immutable
CREATE TRIGGER trg_plan_materialization_immutable BEFORE DELETE OR UPDATE ON execution_plan_materializations FOR EACH ROW EXECUTE FUNCTION fn_decisions_immutable();

-- execution_plan_materializations trg_plan_materialization_no_truncate
CREATE TRIGGER trg_plan_materialization_no_truncate BEFORE TRUNCATE ON execution_plan_materializations FOR EACH STATEMENT EXECUTE FUNCTION fn_decisions_immutable();

-- execution_provider_contract_cutover trg_provider_cutover_immutable
CREATE TRIGGER trg_provider_cutover_immutable BEFORE DELETE OR UPDATE ON execution_provider_contract_cutover FOR EACH ROW EXECUTE FUNCTION fn_decisions_immutable();

-- execution_provider_contract_cutover trg_provider_cutover_no_truncate
CREATE TRIGGER trg_provider_cutover_no_truncate BEFORE TRUNCATE ON execution_provider_contract_cutover FOR EACH STATEMENT EXECUTE FUNCTION fn_decisions_immutable();

-- decisions trg_record_approval_vote
CREATE TRIGGER trg_record_approval_vote AFTER INSERT ON decisions FOR EACH ROW EXECUTE FUNCTION fn_record_approval_vote();

-- execution_recovery_provider_contracts trg_recovery_provider_contract_identity
CREATE TRIGGER trg_recovery_provider_contract_identity BEFORE INSERT ON execution_recovery_provider_contracts FOR EACH ROW EXECUTE FUNCTION fn_recovery_provider_contract_identity();

-- execution_recovery_provider_contracts trg_recovery_provider_contract_immutable
CREATE TRIGGER trg_recovery_provider_contract_immutable BEFORE DELETE OR UPDATE ON execution_recovery_provider_contracts FOR EACH ROW EXECUTE FUNCTION fn_decisions_immutable();

-- execution_recovery_provider_contracts trg_recovery_provider_contract_no_truncate
CREATE TRIGGER trg_recovery_provider_contract_no_truncate BEFORE TRUNCATE ON execution_recovery_provider_contracts FOR EACH STATEMENT EXECUTE FUNCTION fn_decisions_immutable();

-- execution_recovery_commands trg_recovery_provider_contract_required
CREATE CONSTRAINT TRIGGER trg_recovery_provider_contract_required AFTER INSERT ON execution_recovery_commands DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fn_recovery_provider_contract_required();

-- requests trg_request_execution_latch
CREATE TRIGGER trg_request_execution_latch BEFORE UPDATE ON requests FOR EACH ROW EXECUTE FUNCTION fn_request_execution_latch();

-- requests trg_request_milestone_identity
CREATE TRIGGER trg_request_milestone_identity BEFORE UPDATE ON requests FOR EACH ROW EXECUTE FUNCTION fn_request_milestone_identity();

-- requests trg_request_plan_identity
CREATE TRIGGER trg_request_plan_identity BEFORE INSERT OR UPDATE ON requests FOR EACH ROW EXECUTE FUNCTION fn_request_plan_identity();

-- step_instances trg_step_deadlines_immutable
CREATE TRIGGER trg_step_deadlines_immutable BEFORE UPDATE ON step_instances FOR EACH ROW EXECUTE FUNCTION fn_step_deadlines_immutable();

-- step_instances trg_step_escalation_policy_immutable
CREATE TRIGGER trg_step_escalation_policy_immutable BEFORE UPDATE ON step_instances FOR EACH ROW EXECUTE FUNCTION fn_step_escalation_policy_immutable();

-- step_participants trg_step_participants_immutable
CREATE TRIGGER trg_step_participants_immutable BEFORE DELETE OR UPDATE ON step_participants FOR EACH ROW EXECUTE FUNCTION fn_decisions_immutable();

-- step_instances trg_step_policy_immutable
CREATE TRIGGER trg_step_policy_immutable BEFORE UPDATE ON step_instances FOR EACH ROW EXECUTE FUNCTION fn_step_policy_immutable();

-- step_recoveries trg_step_recoveries_immutable
CREATE TRIGGER trg_step_recoveries_immutable BEFORE DELETE OR UPDATE ON step_recoveries FOR EACH ROW EXECUTE FUNCTION fn_decisions_immutable();

-- step_recoveries trg_step_recoveries_no_truncate
CREATE TRIGGER trg_step_recoveries_no_truncate BEFORE TRUNCATE ON step_recoveries FOR EACH STATEMENT EXECUTE FUNCTION fn_decisions_immutable();

-- step_instances trg_step_roster_complete
CREATE CONSTRAINT TRIGGER trg_step_roster_complete AFTER INSERT OR UPDATE ON step_instances DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fn_step_roster_complete();

-- step_approval_votes trg_validate_approval_vote_source
CREATE TRIGGER trg_validate_approval_vote_source BEFORE INSERT ON step_approval_votes FOR EACH ROW EXECUTE FUNCTION fn_validate_approval_vote_source();

-- step_participants trg_validate_step_participant
CREATE TRIGGER trg_validate_step_participant BEFORE INSERT ON step_participants FOR EACH ROW EXECUTE FUNCTION fn_validate_step_participant();
