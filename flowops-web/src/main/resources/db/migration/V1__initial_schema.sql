-- FlowOps fresh database schema. All columns, constraints and indexes are defined in their final form.
-- Legacy evidence tables are intentionally empty compatibility storage, not backfilled history.

-- authentication_refresh_tokens
CREATE TABLE authentication_refresh_tokens (
    token_hash character(64) NOT NULL,
    session_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    consumed_at timestamp with time zone,
    CONSTRAINT authentication_refresh_tokens_check CHECK ((expires_at > created_at))
);

-- authentication_sessions
CREATE TABLE authentication_sessions (
    id uuid NOT NULL,
    user_id bigint NOT NULL,
    credential_fingerprint character(64) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    CONSTRAINT authentication_sessions_check CHECK ((expires_at > created_at))
);

-- clarifications
CREATE TABLE clarifications (
    id bigint NOT NULL,
    request_id bigint NOT NULL,
    asked_by bigint NOT NULL,
    question text NOT NULL,
    answer text,
    answered_by bigint,
    answered_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

-- clarifications_id_seq
ALTER TABLE clarifications ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME clarifications_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

-- decisions
CREATE TABLE decisions (
    id bigint NOT NULL,
    request_id bigint NOT NULL,
    step_instance_id bigint NOT NULL,
    actor_id bigint NOT NULL,
    actor_username character varying(100),
    action character varying(12) NOT NULL,
    justification character varying(1000),
    evidence jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    delegated_to bigint
);

-- decisions_id_seq
CREATE SEQUENCE decisions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- decisions_id_seq
ALTER SEQUENCE decisions_id_seq OWNED BY decisions.id;

-- departments
CREATE TABLE departments (
    id bigint NOT NULL,
    code character varying(32) NOT NULL,
    name character varying(100) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

-- departments_id_seq
CREATE SEQUENCE departments_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- departments_id_seq
ALTER SEQUENCE departments_id_seq OWNED BY departments.id;

-- eligibility_reconciliation_generation
CREATE SEQUENCE eligibility_reconciliation_generation
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- eligibility_reconciliation_queue
CREATE TABLE eligibility_reconciliation_queue (
    request_id bigint NOT NULL,
    generation bigint DEFAULT nextval('eligibility_reconciliation_generation'::regclass) NOT NULL,
    available_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL
);

-- email_log
CREATE TABLE email_log (
    id bigint NOT NULL,
    to_address character varying(256) NOT NULL,
    subject character varying(256) NOT NULL,
    body text NOT NULL,
    status character varying(16) DEFAULT 'DELIVERED'::character varying NOT NULL,
    outbox_id bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

-- email_log_id_seq
ALTER TABLE email_log ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME email_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

-- execution_attempt_baselines
CREATE TABLE execution_attempt_baselines (
    job_id bigint NOT NULL,
    request_id bigint NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    status character varying(32) NOT NULL,
    attempt_count integer NOT NULL,
    first_started_at timestamp with time zone,
    start_inferred boolean NOT NULL,
    claim_token uuid,
    observed_lease_expires_at timestamp with time zone,
    CONSTRAINT execution_attempt_baselines_attempt_count_check CHECK ((attempt_count >= 0))
);

-- execution_attempt_events
CREATE TABLE execution_attempt_events (
    event_id uuid NOT NULL,
    sequence_id bigint NOT NULL,
    request_id bigint NOT NULL,
    job_id bigint NOT NULL,
    claim_token uuid,
    baseline_job_id bigint,
    kind character varying(32) NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    lease_expires_at timestamp with time zone,
    outcome character varying(64),
    details jsonb NOT NULL,
    recovery_command_id uuid,
    CONSTRAINT execution_attempt_events_check CHECK (((((claim_token IS NOT NULL))::integer + ((baseline_job_id IS NOT NULL))::integer) = 1)),
    CONSTRAINT execution_attempt_events_check1 CHECK ((((kind)::text <> 'HEARTBEAT'::text) OR ((claim_token IS NOT NULL) AND (lease_expires_at IS NOT NULL) AND (lease_expires_at > occurred_at)))),
    CONSTRAINT execution_attempt_events_kind_check CHECK (((kind)::text = ANY ((ARRAY['HEARTBEAT'::character varying, 'OUTCOME'::character varying, 'HOLD'::character varying, 'PROVIDER_OBSERVATION'::character varying, 'RETRY_AUTHORIZED'::character varying])::text[])))
);

-- execution_attempt_events_sequence_id_seq
CREATE SEQUENCE execution_attempt_events_sequence_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- execution_attempt_events_sequence_id_seq
ALTER SEQUENCE execution_attempt_events_sequence_id_seq OWNED BY execution_attempt_events.sequence_id;

-- execution_attempt_provider_contracts
CREATE TABLE execution_attempt_provider_contracts (
    claim_token uuid NOT NULL,
    action character varying(64) NOT NULL,
    provider_id character varying(100) NOT NULL,
    revision character varying(100) NOT NULL,
    contract jsonb NOT NULL,
    recorded_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT execution_attempt_provider_contracts_check CHECK ((((contract ->> 'providerId'::text) = (provider_id)::text) AND ((contract ->> 'revision'::text) = (revision)::text)))
);

-- execution_attempts
CREATE TABLE execution_attempts (
    claim_token uuid NOT NULL,
    request_id bigint NOT NULL,
    job_id bigint NOT NULL,
    attempt_number integer NOT NULL,
    idempotency_key character varying(256) NOT NULL,
    action character varying(64) NOT NULL,
    input_payload jsonb NOT NULL,
    correlation_id character varying(100),
    authorized_at timestamp with time zone NOT NULL,
    initial_lease_expires_at timestamp with time zone NOT NULL,
    deadline timestamp with time zone NOT NULL,
    CONSTRAINT execution_attempts_attempt_number_check CHECK ((attempt_number > 0)),
    CONSTRAINT execution_attempts_check CHECK ((initial_lease_expires_at > authorized_at)),
    CONSTRAINT execution_attempts_check1 CHECK ((deadline >= initial_lease_expires_at))
);

-- execution_jobs
CREATE TABLE execution_jobs (
    id bigint NOT NULL,
    idempotency_key character varying(256) NOT NULL,
    request_id bigint NOT NULL,
    action character varying(64) NOT NULL,
    status character varying(32) DEFAULT 'PLANNED'::character varying NOT NULL,
    retry_count integer DEFAULT 0 NOT NULL,
    max_retries integer DEFAULT 5 NOT NULL,
    next_retry_at timestamp with time zone,
    last_error text,
    result jsonb,
    correlation_id character varying(64),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    started_at timestamp with time zone,
    start_inferred boolean DEFAULT false NOT NULL,
    cancelled_at timestamp with time zone,
    cancelled_by bigint,
    attempt_count integer DEFAULT 0 NOT NULL,
    claim_token uuid,
    lease_expires_at timestamp with time zone,
    input_payload jsonb,
    CONSTRAINT ck_execution_cancellation CHECK ((((status)::text <> 'CANCELLED'::text) OR ((cancelled_at IS NOT NULL) AND (started_at IS NULL) AND (attempt_count = 0) AND (retry_count = 0)))),
    CONSTRAINT ck_execution_claim CHECK ((((status)::text <> 'STARTED'::text) OR ((started_at IS NOT NULL) AND (claim_token IS NOT NULL) AND (lease_expires_at IS NOT NULL) AND (attempt_count > 0) AND (input_payload IS NOT NULL)))),
    CONSTRAINT ck_execution_outcome_start CHECK ((((status)::text <> ALL ((ARRAY['COMPLETED'::character varying, 'FAILED'::character varying, 'DEAD_LETTER'::character varying])::text[])) OR ((started_at IS NOT NULL) AND (attempt_count > 0)))),
    CONSTRAINT ck_execution_retry_counters CHECK (((retry_count >= 0) AND (max_retries > 0))),
    CONSTRAINT ck_execution_status CHECK (((status)::text = ANY ((ARRAY['PLANNED'::character varying, 'STARTED'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'DEAD_LETTER'::character varying, 'CANCELLED'::character varying, 'RECONCILIATION_REQUIRED'::character varying])::text[]))),
    CONSTRAINT execution_jobs_attempt_count_check CHECK ((attempt_count >= 0))
);

-- execution_jobs_id_seq
ALTER TABLE execution_jobs ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME execution_jobs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

-- execution_plan_actions
CREATE TABLE execution_plan_actions (
    plan_id uuid NOT NULL,
    "position" integer NOT NULL,
    action character varying(64) NOT NULL,
    idempotency_key character varying(256) NOT NULL,
    input_payload jsonb NOT NULL,
    correlation_id character varying(64),
    CONSTRAINT execution_plan_actions_position_check CHECK (("position" > 0))
);

-- execution_plan_job_bindings
CREATE TABLE execution_plan_job_bindings (
    plan_id uuid NOT NULL,
    action character varying(64) NOT NULL,
    job_id bigint NOT NULL
);

-- execution_plan_materializations
CREATE TABLE execution_plan_materializations (
    plan_id uuid NOT NULL,
    action character varying(64) NOT NULL,
    job_id bigint,
    job_created_at timestamp with time zone,
    initial_max_retries integer,
    unstarted_at_capture boolean NOT NULL,
    provenance character varying(32) NOT NULL,
    observed_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT execution_plan_materializations_check CHECK (((NOT unstarted_at_capture) OR ((job_id IS NOT NULL) AND (job_created_at IS NOT NULL) AND (initial_max_retries > 0)))),
    CONSTRAINT execution_plan_materializations_provenance_check CHECK (((provenance)::text = ANY ((ARRAY['RECORDED'::character varying, 'UPGRADE_OBSERVED'::character varying, 'UNKNOWN_AT_UPGRADE'::character varying])::text[])))
);

-- execution_plans
CREATE TABLE execution_plans (
    id uuid NOT NULL,
    request_id bigint NOT NULL,
    type_id bigint NOT NULL,
    type_code character varying(64) NOT NULL,
    source_type_updated_at timestamp with time zone,
    planned_at timestamp with time zone NOT NULL,
    approval jsonb NOT NULL,
    expected_actions integer NOT NULL,
    CONSTRAINT execution_plans_expected_actions_check CHECK ((expected_actions >= 0))
);

-- execution_provider_contract_cutover
CREATE TABLE execution_provider_contract_cutover (
    singleton boolean NOT NULL,
    activated_at timestamp with time zone NOT NULL,
    CONSTRAINT execution_provider_contract_cutover_singleton_check CHECK (singleton)
);

-- execution_recovery_commands
CREATE TABLE execution_recovery_commands (
    command_id uuid NOT NULL,
    sequence_id bigint NOT NULL,
    request_id bigint NOT NULL,
    job_id bigint NOT NULL,
    kind character varying(16) NOT NULL,
    actor_id bigint NOT NULL,
    actor_name text NOT NULL,
    reason text NOT NULL,
    expected_version bigint NOT NULL,
    created_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    attempt_count integer NOT NULL,
    claim_token uuid,
    CONSTRAINT execution_recovery_commands_attempt_count_check CHECK ((attempt_count >= 0)),
    CONSTRAINT execution_recovery_commands_check CHECK ((expires_at > created_at)),
    CONSTRAINT execution_recovery_commands_expected_version_check CHECK ((expected_version >= 0)),
    CONSTRAINT execution_recovery_commands_kind_check CHECK (((kind)::text = ANY ((ARRAY['RECONCILE'::character varying, 'RETRY'::character varying])::text[]))),
    CONSTRAINT execution_recovery_commands_reason_check CHECK (((length(TRIM(BOTH FROM reason)) >= 1) AND (length(TRIM(BOTH FROM reason)) <= 1000)))
);

-- execution_recovery_commands_sequence_id_seq
CREATE SEQUENCE execution_recovery_commands_sequence_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- execution_recovery_commands_sequence_id_seq
ALTER SEQUENCE execution_recovery_commands_sequence_id_seq OWNED BY execution_recovery_commands.sequence_id;

-- execution_recovery_observations
CREATE TABLE execution_recovery_observations (
    command_id uuid NOT NULL,
    recorded_at timestamp with time zone NOT NULL,
    disposition character varying(32) NOT NULL,
    provider_outcome character varying(32) NOT NULL,
    evidence_reference text,
    result jsonb NOT NULL,
    failure_type text,
    CONSTRAINT ck_recovery_authoritative_evidence CHECK ((((provider_outcome)::text <> ALL ((ARRAY['SUCCEEDED'::character varying, 'SAFE_TO_RETRY'::character varying])::text[])) OR ((length(TRIM(BOTH FROM evidence_reference)) > 0) AND (evidence_reference IS NOT NULL)))),
    CONSTRAINT ck_recovery_completion CHECK ((((disposition)::text <> 'COMPLETED'::text) OR ((provider_outcome)::text = 'SUCCEEDED'::text))),
    CONSTRAINT ck_recovery_retry CHECK ((((disposition)::text <> 'RETRY_AUTHORIZED'::text) OR ((provider_outcome)::text = ANY ((ARRAY['SAFE_TO_RETRY'::character varying, 'UNKNOWN'::character varying])::text[])))),
    CONSTRAINT execution_recovery_observations_disposition_check CHECK (((disposition)::text = ANY ((ARRAY['OBSERVED'::character varying, 'COMPLETED'::character varying, 'RETRY_AUTHORIZED'::character varying, 'PROVIDER_ERROR'::character varying, 'STALE'::character varying, 'AUTHORITY_REVOKED'::character varying])::text[]))),
    CONSTRAINT execution_recovery_observations_provider_outcome_check CHECK (((provider_outcome)::text = ANY ((ARRAY['SUCCEEDED'::character varying, 'SAFE_TO_RETRY'::character varying, 'IN_PROGRESS'::character varying, 'UNKNOWN'::character varying])::text[])))
);

-- execution_recovery_provider_contracts
CREATE TABLE execution_recovery_provider_contracts (
    command_id uuid NOT NULL,
    action character varying(64) NOT NULL,
    provider_id character varying(100) NOT NULL,
    revision character varying(100) NOT NULL,
    contract jsonb NOT NULL,
    recorded_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT execution_recovery_provider_contracts_check CHECK ((((contract ->> 'providerId'::text) = (provider_id)::text) AND ((contract ->> 'revision'::text) = (revision)::text)))
);

-- legacy_all_of_definitions
CREATE TABLE legacy_all_of_definitions (
    step_id bigint NOT NULL,
    workflow_id bigint,
    required_approvals integer,
    original_rule character varying(8),
    migrated_at timestamp with time zone
);

-- legacy_duplicate_approval_votes
CREATE VIEW legacy_duplicate_approval_votes AS
 SELECT step_instance_id,
    actor_id,
    count(*) AS approval_count
   FROM decisions
  WHERE ((action)::text = 'APPROVE'::text)
  GROUP BY step_instance_id, actor_id
 HAVING (count(*) > 1);

-- legacy_execution_cutover
CREATE TABLE legacy_execution_cutover (
    job_id bigint NOT NULL,
    request_id bigint,
    status character varying(32),
    retry_count integer,
    completed_at timestamp with time zone,
    observed_at timestamp with time zone,
    attempt_observed boolean
);

-- legacy_plan_dispositions
CREATE TABLE legacy_plan_dispositions (
    command_id uuid NOT NULL,
    request_id bigint NOT NULL,
    plan_id uuid,
    actor_id bigint NOT NULL,
    actor_name text NOT NULL,
    expected_version bigint NOT NULL,
    mode character varying(32) NOT NULL,
    reason text NOT NULL,
    evidence_reference text NOT NULL,
    recorded_at timestamp with time zone NOT NULL,
    payload jsonb NOT NULL,
    CONSTRAINT legacy_plan_dispositions_check CHECK ((((mode)::text = 'ADOPT'::text) = (plan_id IS NOT NULL))),
    CONSTRAINT legacy_plan_dispositions_evidence_reference_check CHECK (((length(TRIM(BOTH FROM evidence_reference)) >= 1) AND (length(TRIM(BOTH FROM evidence_reference)) <= 1000))),
    CONSTRAINT legacy_plan_dispositions_expected_version_check CHECK ((expected_version >= 0)),
    CONSTRAINT legacy_plan_dispositions_mode_check CHECK (((mode)::text = ANY ((ARRAY['ADOPT'::character varying, 'KEEP_QUARANTINED'::character varying])::text[]))),
    CONSTRAINT legacy_plan_dispositions_payload_check CHECK ((jsonb_typeof(payload) = 'object'::text)),
    CONSTRAINT legacy_plan_dispositions_reason_check CHECK (((length(TRIM(BOTH FROM reason)) >= 1) AND (length(TRIM(BOTH FROM reason)) <= 1000)))
);

-- legacy_plan_provider_bindings
CREATE TABLE legacy_plan_provider_bindings (
    job_id bigint NOT NULL,
    disposition_id uuid NOT NULL,
    provider_id character varying(100) NOT NULL,
    CONSTRAINT legacy_plan_provider_bindings_provider_id_check CHECK ((length(TRIM(BOTH FROM provider_id)) > 0))
);

-- lifecycle_audit_events
CREATE TABLE lifecycle_audit_events (
    sequence_id bigint NOT NULL,
    event_id uuid NOT NULL,
    event_key text NOT NULL,
    request_id bigint NOT NULL,
    event_type character varying(32) NOT NULL,
    action character varying(64) NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    actor_id bigint,
    actor_name character varying(100),
    actor_kind character varying(16) NOT NULL,
    correlation_id character varying(64),
    remote_address character varying(64),
    user_agent character varying(512),
    reason character varying(1000),
    summary text NOT NULL,
    before_data jsonb DEFAULT '{}'::jsonb NOT NULL,
    after_data jsonb DEFAULT '{}'::jsonb NOT NULL,
    details jsonb DEFAULT '{}'::jsonb NOT NULL,
    provenance character varying(32) NOT NULL,
    CONSTRAINT lifecycle_audit_events_actor_kind_check CHECK (((actor_kind)::text = ANY ((ARRAY['HUMAN'::character varying, 'SYSTEM'::character varying, 'UNKNOWN'::character varying])::text[]))),
    CONSTRAINT lifecycle_audit_events_after_data_check CHECK ((jsonb_typeof(after_data) = 'object'::text)),
    CONSTRAINT lifecycle_audit_events_before_data_check CHECK ((jsonb_typeof(before_data) = 'object'::text)),
    CONSTRAINT lifecycle_audit_events_details_check CHECK ((jsonb_typeof(details) = 'object'::text)),
    CONSTRAINT lifecycle_audit_events_provenance_check CHECK (((provenance)::text = ANY ((ARRAY['RECORDED'::character varying, 'LEGACY_RECONSTRUCTED'::character varying, 'LEGACY_BASELINE'::character varying])::text[])))
);

-- lifecycle_audit_events_sequence_id_seq
CREATE SEQUENCE lifecycle_audit_events_sequence_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- lifecycle_audit_events_sequence_id_seq
ALTER SEQUENCE lifecycle_audit_events_sequence_id_seq OWNED BY lifecycle_audit_events.sequence_id;

-- notifications
CREATE TABLE notifications (
    id bigint NOT NULL,
    recipient_id bigint NOT NULL,
    type character varying(32) NOT NULL,
    title character varying(256) NOT NULL,
    body text,
    request_id bigint,
    read_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

-- notifications_id_seq
ALTER TABLE notifications ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME notifications_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

-- outbox
CREATE TABLE outbox (
    id bigint NOT NULL,
    idempotency_key character varying(256) NOT NULL,
    channel character varying(32) NOT NULL,
    recipient_id bigint NOT NULL,
    payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    retry_count integer DEFAULT 0 NOT NULL,
    max_retries integer DEFAULT 5 NOT NULL,
    next_retry_at timestamp with time zone,
    last_error text,
    correlation_id character varying(64),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    sent_at timestamp with time zone
);

-- outbox_id_seq
ALTER TABLE outbox ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME outbox_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

-- password_reset_tokens
CREATE TABLE password_reset_tokens (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    token_hash character varying(64) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    used_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

-- password_reset_tokens_id_seq
CREATE SEQUENCE password_reset_tokens_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- password_reset_tokens_id_seq
ALTER SEQUENCE password_reset_tokens_id_seq OWNED BY password_reset_tokens.id;

-- permissions
CREATE TABLE permissions (
    id bigint NOT NULL,
    code character varying(64) NOT NULL,
    name character varying(100) NOT NULL,
    description text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    scope_type character varying(32),
    scope_value character varying(128)
);

-- permissions_id_seq
CREATE SEQUENCE permissions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- permissions_id_seq
ALTER SEQUENCE permissions_id_seq OWNED BY permissions.id;

-- request_audit_coverage
CREATE TABLE request_audit_coverage (
    request_id bigint NOT NULL,
    started_at timestamp with time zone NOT NULL,
    legacy_prefix boolean NOT NULL
);

-- request_types
CREATE TABLE request_types (
    id bigint NOT NULL,
    code character varying(32) NOT NULL,
    name character varying(100) NOT NULL,
    description text,
    owning_dept_id bigint,
    default_workflow_id bigint,
    required_fields jsonb DEFAULT '{}'::jsonb NOT NULL,
    validation_rules jsonb DEFAULT '{}'::jsonb NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    execution_actions jsonb DEFAULT '[]'::jsonb NOT NULL
);

-- request_types_id_seq
CREATE SEQUENCE request_types_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- request_types_id_seq
ALTER SEQUENCE request_types_id_seq OWNED BY request_types.id;

-- requests
CREATE TABLE requests (
    id bigint NOT NULL,
    type_id bigint NOT NULL,
    requester_id bigint NOT NULL,
    status character varying(16) DEFAULT 'DRAFT'::character varying NOT NULL,
    payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    correlation_id character varying(36),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    submitted_at timestamp with time zone,
    execution_planned_at timestamp with time zone,
    execution_started_at timestamp with time zone,
    execution_start_unknown boolean DEFAULT false NOT NULL,
    execution_start_inferred boolean DEFAULT false NOT NULL,
    execution_plan_id uuid,
    outcome character varying(16),
    approved_at timestamp with time zone,
    rejected_at timestamp with time zone,
    executed_at timestamp with time zone,
    cancelled_at timestamp with time zone,
    expired_at timestamp with time zone,
    closed_at timestamp with time zone,
    legacy_plan_disposition_id uuid,
    CONSTRAINT requests_outcome_check CHECK (((outcome)::text = ANY ((ARRAY['EXECUTED'::character varying, 'REJECTED'::character varying, 'CANCELLED'::character varying, 'EXPIRED'::character varying])::text[])))
);

-- requests_id_seq
CREATE SEQUENCE requests_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- requests_id_seq
ALTER SEQUENCE requests_id_seq OWNED BY requests.id;

-- role_permissions
CREATE TABLE role_permissions (
    role_id bigint NOT NULL,
    permission_id bigint NOT NULL
);

-- roles
CREATE TABLE roles (
    id bigint NOT NULL,
    code character varying(32) NOT NULL,
    name character varying(100) NOT NULL,
    description text,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

-- roles_id_seq
CREATE SEQUENCE roles_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- roles_id_seq
ALTER SEQUENCE roles_id_seq OWNED BY roles.id;

-- step_approval_votes
CREATE TABLE step_approval_votes (
    step_instance_id bigint NOT NULL,
    actor_id bigint NOT NULL,
    decision_id bigint NOT NULL,
    created_at timestamp with time zone NOT NULL,
    slot_id uuid
);

-- step_instances
CREATE TABLE step_instances (
    id bigint NOT NULL,
    request_id bigint NOT NULL,
    step_id bigint NOT NULL,
    "position" integer NOT NULL,
    state character varying(12) DEFAULT 'PENDING'::character varying NOT NULL,
    rule character varying(8) NOT NULL,
    required_approvals integer NOT NULL,
    condition jsonb,
    skip_reason character varying(200),
    blocked_reason character varying(200),
    satisfied_by bigint,
    satisfied_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    step_code character varying(64) NOT NULL,
    step_name character varying(100) NOT NULL,
    required_role character varying(64) NOT NULL,
    sla_minutes integer,
    escalation_role character varying(64),
    roster_snapshotted boolean DEFAULT true NOT NULL,
    roster_size integer,
    block_reason_code character varying(32),
    blocked_at timestamp with time zone,
    block_episode integer DEFAULT 0 NOT NULL,
    recovery_owner_id bigint,
    recovery_team character varying(64),
    recovered_at timestamp with time zone,
    activated_at timestamp with time zone NOT NULL,
    review_due_at timestamp with time zone,
    next_escalation_at timestamp with time zone,
    expires_at timestamp with time zone,
    escalation_level integer DEFAULT 0 NOT NULL,
    recovery_owner_revision integer DEFAULT 0 NOT NULL,
    recovery_owner_unavailable boolean DEFAULT false NOT NULL,
    escalation_interval_minutes integer DEFAULT 60 NOT NULL,
    max_escalation_levels integer,
    CONSTRAINT ck_all_of_roster_threshold CHECK (((NOT roster_snapshotted) OR ((rule)::text <> 'ALL_OF'::text) OR (roster_size IS NULL) OR (required_approvals = GREATEST(1, roster_size)))),
    CONSTRAINT ck_recovery_owner_availability CHECK (((NOT recovery_owner_unavailable) OR (recovery_owner_id IS NULL))),
    CONSTRAINT ck_step_block_metadata CHECK ((((state)::text <> 'BLOCKED'::text) OR ((block_episode > 0) AND (block_reason_code IS NOT NULL) AND (recovery_team IS NOT NULL) AND (((block_reason_code)::text = 'LEGACY_UNCLASSIFIED'::text) OR ((blocked_at IS NOT NULL) AND ((recovery_owner_id IS NOT NULL) OR recovery_owner_unavailable)))))),
    CONSTRAINT ck_step_block_reason CHECK (((block_reason_code IS NULL) OR ((block_reason_code)::text = ANY ((ARRAY['NO_ELIGIBLE_APPROVER'::character varying, 'INSUFFICIENT_QUORUM'::character varying, 'PARTICIPANT_UNAVAILABLE'::character varying, 'LEGACY_UNCLASSIFIED'::character varying])::text[])))),
    CONSTRAINT ck_step_roster_initialized CHECK (((NOT roster_snapshotted) OR ((state)::text = 'SKIPPED'::text) OR (roster_size IS NOT NULL))),
    CONSTRAINT ck_step_state CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'BLOCKED'::character varying, 'SATISFIED'::character varying, 'SKIPPED'::character varying, 'REJECTED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT step_instances_block_episode_check CHECK ((block_episode >= 0)),
    CONSTRAINT step_instances_escalation_interval_minutes_check CHECK ((escalation_interval_minutes > 0)),
    CONSTRAINT step_instances_escalation_level_check CHECK ((escalation_level >= 0)),
    CONSTRAINT step_instances_max_escalation_levels_check CHECK ((max_escalation_levels > 0)),
    CONSTRAINT step_instances_recovery_owner_revision_check CHECK ((recovery_owner_revision >= 0)),
    CONSTRAINT step_instances_roster_size_check CHECK ((roster_size >= 0))
);

-- step_instances_id_seq
CREATE SEQUENCE step_instances_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- step_instances_id_seq
ALTER SEQUENCE step_instances_id_seq OWNED BY step_instances.id;

-- step_participants
CREATE TABLE step_participants (
    id uuid NOT NULL,
    slot_id uuid NOT NULL,
    step_instance_id bigint NOT NULL,
    user_id bigint NOT NULL,
    predecessor_id uuid,
    decision_id bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    recovery_id uuid,
    CONSTRAINT ck_step_assignment_source CHECK ((((predecessor_id IS NULL) AND (decision_id IS NULL) AND (recovery_id IS NULL) AND (id = slot_id)) OR ((predecessor_id IS NOT NULL) AND (decision_id IS NOT NULL) AND (recovery_id IS NULL) AND (id <> slot_id)) OR ((recovery_id IS NOT NULL) AND (decision_id IS NULL) AND (((predecessor_id IS NULL) AND (id = slot_id)) OR ((predecessor_id IS NOT NULL) AND (id <> slot_id))))))
);

-- step_recoveries
CREATE TABLE step_recoveries (
    id uuid NOT NULL,
    step_instance_id bigint NOT NULL,
    block_episode integer NOT NULL,
    actor_id bigint,
    reason character varying(1000) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT step_recoveries_block_episode_check CHECK ((block_episode > 0)),
    CONSTRAINT step_recoveries_reason_check CHECK ((length(btrim((reason)::text)) > 0))
);

-- user_roles
CREATE TABLE user_roles (
    user_id bigint NOT NULL,
    role_id bigint NOT NULL
);

-- users
CREATE TABLE users (
    id bigint NOT NULL,
    username character varying(64) NOT NULL,
    display_name character varying(100) NOT NULL,
    email character varying(255),
    department_id bigint,
    password_hash character varying(100) NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

-- users_id_seq
CREATE SEQUENCE users_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- users_id_seq
ALTER SEQUENCE users_id_seq OWNED BY users.id;

-- workflow_steps
CREATE TABLE workflow_steps (
    id bigint NOT NULL,
    workflow_id bigint NOT NULL,
    "position" integer NOT NULL,
    code character varying(64) NOT NULL,
    name character varying(100) NOT NULL,
    required_role character varying(64) NOT NULL,
    completion_rule character varying(8) DEFAULT 'ANY_OF'::character varying NOT NULL,
    required_approvals integer DEFAULT 1 NOT NULL,
    condition jsonb,
    sla_minutes integer,
    escalation_role character varying(64),
    is_terminal boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expiry_minutes integer,
    escalation_interval_minutes integer DEFAULT 60 NOT NULL,
    max_escalation_levels integer,
    CONSTRAINT workflow_steps_escalation_interval_minutes_check CHECK ((escalation_interval_minutes > 0)),
    CONSTRAINT workflow_steps_expiry_minutes_check CHECK ((expiry_minutes > 0)),
    CONSTRAINT workflow_steps_max_escalation_levels_check CHECK ((max_escalation_levels > 0))
);

-- workflow_steps_id_seq
CREATE SEQUENCE workflow_steps_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- workflow_steps_id_seq
ALTER SEQUENCE workflow_steps_id_seq OWNED BY workflow_steps.id;

-- workflows
CREATE TABLE workflows (
    id bigint NOT NULL,
    code character varying(64) NOT NULL,
    name character varying(100) NOT NULL,
    description text,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

-- workflows_id_seq
CREATE SEQUENCE workflows_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- workflows_id_seq
ALTER SEQUENCE workflows_id_seq OWNED BY workflows.id;

-- decisions id
ALTER TABLE ONLY decisions ALTER COLUMN id SET DEFAULT nextval('decisions_id_seq'::regclass);

-- departments id
ALTER TABLE ONLY departments ALTER COLUMN id SET DEFAULT nextval('departments_id_seq'::regclass);

-- execution_attempt_events sequence_id
ALTER TABLE ONLY execution_attempt_events ALTER COLUMN sequence_id SET DEFAULT nextval('execution_attempt_events_sequence_id_seq'::regclass);

-- execution_recovery_commands sequence_id
ALTER TABLE ONLY execution_recovery_commands ALTER COLUMN sequence_id SET DEFAULT nextval('execution_recovery_commands_sequence_id_seq'::regclass);

-- lifecycle_audit_events sequence_id
ALTER TABLE ONLY lifecycle_audit_events ALTER COLUMN sequence_id SET DEFAULT nextval('lifecycle_audit_events_sequence_id_seq'::regclass);

-- password_reset_tokens id
ALTER TABLE ONLY password_reset_tokens ALTER COLUMN id SET DEFAULT nextval('password_reset_tokens_id_seq'::regclass);

-- permissions id
ALTER TABLE ONLY permissions ALTER COLUMN id SET DEFAULT nextval('permissions_id_seq'::regclass);

-- request_types id
ALTER TABLE ONLY request_types ALTER COLUMN id SET DEFAULT nextval('request_types_id_seq'::regclass);

-- requests id
ALTER TABLE ONLY requests ALTER COLUMN id SET DEFAULT nextval('requests_id_seq'::regclass);

-- roles id
ALTER TABLE ONLY roles ALTER COLUMN id SET DEFAULT nextval('roles_id_seq'::regclass);

-- step_instances id
ALTER TABLE ONLY step_instances ALTER COLUMN id SET DEFAULT nextval('step_instances_id_seq'::regclass);

-- users id
ALTER TABLE ONLY users ALTER COLUMN id SET DEFAULT nextval('users_id_seq'::regclass);

-- workflow_steps id
ALTER TABLE ONLY workflow_steps ALTER COLUMN id SET DEFAULT nextval('workflow_steps_id_seq'::regclass);

-- workflows id
ALTER TABLE ONLY workflows ALTER COLUMN id SET DEFAULT nextval('workflows_id_seq'::regclass);

-- authentication_refresh_tokens authentication_refresh_tokens_pkey
ALTER TABLE ONLY authentication_refresh_tokens
    ADD CONSTRAINT authentication_refresh_tokens_pkey PRIMARY KEY (token_hash);

-- authentication_sessions authentication_sessions_pkey
ALTER TABLE ONLY authentication_sessions
    ADD CONSTRAINT authentication_sessions_pkey PRIMARY KEY (id);

-- clarifications clarifications_pkey
ALTER TABLE ONLY clarifications
    ADD CONSTRAINT clarifications_pkey PRIMARY KEY (id);

-- decisions decisions_pkey
ALTER TABLE ONLY decisions
    ADD CONSTRAINT decisions_pkey PRIMARY KEY (id);

-- departments departments_code_key
ALTER TABLE ONLY departments
    ADD CONSTRAINT departments_code_key UNIQUE (code);

-- departments departments_pkey
ALTER TABLE ONLY departments
    ADD CONSTRAINT departments_pkey PRIMARY KEY (id);

-- eligibility_reconciliation_queue eligibility_reconciliation_queue_pkey
ALTER TABLE ONLY eligibility_reconciliation_queue
    ADD CONSTRAINT eligibility_reconciliation_queue_pkey PRIMARY KEY (request_id);

-- email_log email_log_pkey
ALTER TABLE ONLY email_log
    ADD CONSTRAINT email_log_pkey PRIMARY KEY (id);

-- execution_attempt_baselines execution_attempt_baselines_pkey
ALTER TABLE ONLY execution_attempt_baselines
    ADD CONSTRAINT execution_attempt_baselines_pkey PRIMARY KEY (job_id);

-- execution_attempt_events execution_attempt_events_pkey
ALTER TABLE ONLY execution_attempt_events
    ADD CONSTRAINT execution_attempt_events_pkey PRIMARY KEY (event_id);

-- execution_attempt_events execution_attempt_events_sequence_id_key
ALTER TABLE ONLY execution_attempt_events
    ADD CONSTRAINT execution_attempt_events_sequence_id_key UNIQUE (sequence_id);

-- execution_attempt_provider_contracts execution_attempt_provider_contracts_pkey
ALTER TABLE ONLY execution_attempt_provider_contracts
    ADD CONSTRAINT execution_attempt_provider_contracts_pkey PRIMARY KEY (claim_token);

-- execution_attempts execution_attempts_job_id_attempt_number_key
ALTER TABLE ONLY execution_attempts
    ADD CONSTRAINT execution_attempts_job_id_attempt_number_key UNIQUE (job_id, attempt_number);

-- execution_attempts execution_attempts_pkey
ALTER TABLE ONLY execution_attempts
    ADD CONSTRAINT execution_attempts_pkey PRIMARY KEY (claim_token);

-- execution_jobs execution_jobs_idempotency_key_key
ALTER TABLE ONLY execution_jobs
    ADD CONSTRAINT execution_jobs_idempotency_key_key UNIQUE (idempotency_key);

-- execution_jobs execution_jobs_pkey
ALTER TABLE ONLY execution_jobs
    ADD CONSTRAINT execution_jobs_pkey PRIMARY KEY (id);

-- execution_plan_actions execution_plan_actions_idempotency_key_key
ALTER TABLE ONLY execution_plan_actions
    ADD CONSTRAINT execution_plan_actions_idempotency_key_key UNIQUE (idempotency_key);

-- execution_plan_actions execution_plan_actions_pkey
ALTER TABLE ONLY execution_plan_actions
    ADD CONSTRAINT execution_plan_actions_pkey PRIMARY KEY (plan_id, action);

-- execution_plan_actions execution_plan_actions_plan_id_position_key
ALTER TABLE ONLY execution_plan_actions
    ADD CONSTRAINT execution_plan_actions_plan_id_position_key UNIQUE (plan_id, "position");

-- execution_plan_job_bindings execution_plan_job_bindings_job_id_key
ALTER TABLE ONLY execution_plan_job_bindings
    ADD CONSTRAINT execution_plan_job_bindings_job_id_key UNIQUE (job_id);

-- execution_plan_job_bindings execution_plan_job_bindings_pkey
ALTER TABLE ONLY execution_plan_job_bindings
    ADD CONSTRAINT execution_plan_job_bindings_pkey PRIMARY KEY (plan_id, action);

-- execution_plan_materializations execution_plan_materializations_job_id_key
ALTER TABLE ONLY execution_plan_materializations
    ADD CONSTRAINT execution_plan_materializations_job_id_key UNIQUE (job_id);

-- execution_plan_materializations execution_plan_materializations_pkey
ALTER TABLE ONLY execution_plan_materializations
    ADD CONSTRAINT execution_plan_materializations_pkey PRIMARY KEY (plan_id, action);

-- execution_plans execution_plans_pkey
ALTER TABLE ONLY execution_plans
    ADD CONSTRAINT execution_plans_pkey PRIMARY KEY (id);

-- execution_plans execution_plans_request_id_key
ALTER TABLE ONLY execution_plans
    ADD CONSTRAINT execution_plans_request_id_key UNIQUE (request_id);

-- execution_provider_contract_cutover execution_provider_contract_cutover_pkey
ALTER TABLE ONLY execution_provider_contract_cutover
    ADD CONSTRAINT execution_provider_contract_cutover_pkey PRIMARY KEY (singleton);

-- execution_recovery_commands execution_recovery_commands_pkey
ALTER TABLE ONLY execution_recovery_commands
    ADD CONSTRAINT execution_recovery_commands_pkey PRIMARY KEY (command_id);

-- execution_recovery_commands execution_recovery_commands_sequence_id_key
ALTER TABLE ONLY execution_recovery_commands
    ADD CONSTRAINT execution_recovery_commands_sequence_id_key UNIQUE (sequence_id);

-- execution_recovery_observations execution_recovery_observations_pkey
ALTER TABLE ONLY execution_recovery_observations
    ADD CONSTRAINT execution_recovery_observations_pkey PRIMARY KEY (command_id);

-- execution_recovery_provider_contracts execution_recovery_provider_contracts_pkey
ALTER TABLE ONLY execution_recovery_provider_contracts
    ADD CONSTRAINT execution_recovery_provider_contracts_pkey PRIMARY KEY (command_id);

-- legacy_all_of_definitions legacy_all_of_definitions_pkey
ALTER TABLE ONLY legacy_all_of_definitions
    ADD CONSTRAINT legacy_all_of_definitions_pkey PRIMARY KEY (step_id);

-- legacy_execution_cutover legacy_execution_cutover_pkey
ALTER TABLE ONLY legacy_execution_cutover
    ADD CONSTRAINT legacy_execution_cutover_pkey PRIMARY KEY (job_id);

-- legacy_plan_dispositions legacy_plan_dispositions_pkey
ALTER TABLE ONLY legacy_plan_dispositions
    ADD CONSTRAINT legacy_plan_dispositions_pkey PRIMARY KEY (command_id);

-- legacy_plan_dispositions legacy_plan_dispositions_plan_id_key
ALTER TABLE ONLY legacy_plan_dispositions
    ADD CONSTRAINT legacy_plan_dispositions_plan_id_key UNIQUE (plan_id);

-- legacy_plan_provider_bindings legacy_plan_provider_bindings_pkey
ALTER TABLE ONLY legacy_plan_provider_bindings
    ADD CONSTRAINT legacy_plan_provider_bindings_pkey PRIMARY KEY (job_id);

-- lifecycle_audit_events lifecycle_audit_events_event_id_key
ALTER TABLE ONLY lifecycle_audit_events
    ADD CONSTRAINT lifecycle_audit_events_event_id_key UNIQUE (event_id);

-- lifecycle_audit_events lifecycle_audit_events_event_key_key
ALTER TABLE ONLY lifecycle_audit_events
    ADD CONSTRAINT lifecycle_audit_events_event_key_key UNIQUE (event_key);

-- lifecycle_audit_events lifecycle_audit_events_pkey
ALTER TABLE ONLY lifecycle_audit_events
    ADD CONSTRAINT lifecycle_audit_events_pkey PRIMARY KEY (sequence_id);

-- notifications notifications_pkey
ALTER TABLE ONLY notifications
    ADD CONSTRAINT notifications_pkey PRIMARY KEY (id);

-- outbox outbox_idempotency_key_key
ALTER TABLE ONLY outbox
    ADD CONSTRAINT outbox_idempotency_key_key UNIQUE (idempotency_key);

-- outbox outbox_pkey
ALTER TABLE ONLY outbox
    ADD CONSTRAINT outbox_pkey PRIMARY KEY (id);

-- password_reset_tokens password_reset_tokens_pkey
ALTER TABLE ONLY password_reset_tokens
    ADD CONSTRAINT password_reset_tokens_pkey PRIMARY KEY (id);

-- password_reset_tokens password_reset_tokens_token_hash_key
ALTER TABLE ONLY password_reset_tokens
    ADD CONSTRAINT password_reset_tokens_token_hash_key UNIQUE (token_hash);

-- permissions permissions_pkey
ALTER TABLE ONLY permissions
    ADD CONSTRAINT permissions_pkey PRIMARY KEY (id);

-- request_audit_coverage request_audit_coverage_pkey
ALTER TABLE ONLY request_audit_coverage
    ADD CONSTRAINT request_audit_coverage_pkey PRIMARY KEY (request_id);

-- request_types request_types_code_key
ALTER TABLE ONLY request_types
    ADD CONSTRAINT request_types_code_key UNIQUE (code);

-- request_types request_types_pkey
ALTER TABLE ONLY request_types
    ADD CONSTRAINT request_types_pkey PRIMARY KEY (id);

-- requests requests_execution_plan_id_key
ALTER TABLE ONLY requests
    ADD CONSTRAINT requests_execution_plan_id_key UNIQUE (execution_plan_id);

-- requests requests_legacy_plan_disposition_id_key
ALTER TABLE ONLY requests
    ADD CONSTRAINT requests_legacy_plan_disposition_id_key UNIQUE (legacy_plan_disposition_id);

-- requests requests_pkey
ALTER TABLE ONLY requests
    ADD CONSTRAINT requests_pkey PRIMARY KEY (id);

-- role_permissions role_permissions_pkey
ALTER TABLE ONLY role_permissions
    ADD CONSTRAINT role_permissions_pkey PRIMARY KEY (role_id, permission_id);

-- roles roles_code_key
ALTER TABLE ONLY roles
    ADD CONSTRAINT roles_code_key UNIQUE (code);

-- roles roles_pkey
ALTER TABLE ONLY roles
    ADD CONSTRAINT roles_pkey PRIMARY KEY (id);

-- step_approval_votes step_approval_votes_decision_id_key
ALTER TABLE ONLY step_approval_votes
    ADD CONSTRAINT step_approval_votes_decision_id_key UNIQUE (decision_id);

-- step_approval_votes step_approval_votes_pkey
ALTER TABLE ONLY step_approval_votes
    ADD CONSTRAINT step_approval_votes_pkey PRIMARY KEY (step_instance_id, actor_id);

-- step_instances step_instances_pkey
ALTER TABLE ONLY step_instances
    ADD CONSTRAINT step_instances_pkey PRIMARY KEY (id);

-- step_participants step_participants_decision_id_key
ALTER TABLE ONLY step_participants
    ADD CONSTRAINT step_participants_decision_id_key UNIQUE (decision_id);

-- step_participants step_participants_id_step_instance_id_slot_id_key
ALTER TABLE ONLY step_participants
    ADD CONSTRAINT step_participants_id_step_instance_id_slot_id_key UNIQUE (id, step_instance_id, slot_id);

-- step_participants step_participants_pkey
ALTER TABLE ONLY step_participants
    ADD CONSTRAINT step_participants_pkey PRIMARY KEY (id);

-- step_participants step_participants_predecessor_id_key
ALTER TABLE ONLY step_participants
    ADD CONSTRAINT step_participants_predecessor_id_key UNIQUE (predecessor_id);

-- step_participants step_participants_step_instance_id_user_id_key
ALTER TABLE ONLY step_participants
    ADD CONSTRAINT step_participants_step_instance_id_user_id_key UNIQUE (step_instance_id, user_id);

-- step_recoveries step_recoveries_pkey
ALTER TABLE ONLY step_recoveries
    ADD CONSTRAINT step_recoveries_pkey PRIMARY KEY (id);

-- step_recoveries step_recoveries_step_instance_id_block_episode_key
ALTER TABLE ONLY step_recoveries
    ADD CONSTRAINT step_recoveries_step_instance_id_block_episode_key UNIQUE (step_instance_id, block_episode);

-- user_roles user_roles_pkey
ALTER TABLE ONLY user_roles
    ADD CONSTRAINT user_roles_pkey PRIMARY KEY (user_id, role_id);

-- users users_email_key
ALTER TABLE ONLY users
    ADD CONSTRAINT users_email_key UNIQUE (email);

-- users users_pkey
ALTER TABLE ONLY users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);

-- users users_username_key
ALTER TABLE ONLY users
    ADD CONSTRAINT users_username_key UNIQUE (username);

-- workflow_steps workflow_steps_pkey
ALTER TABLE ONLY workflow_steps
    ADD CONSTRAINT workflow_steps_pkey PRIMARY KEY (id);

-- workflow_steps workflow_steps_workflow_id_position_key
ALTER TABLE ONLY workflow_steps
    ADD CONSTRAINT workflow_steps_workflow_id_position_key UNIQUE (workflow_id, "position");

-- workflows workflows_code_key
ALTER TABLE ONLY workflows
    ADD CONSTRAINT workflows_code_key UNIQUE (code);

-- workflows workflows_pkey
ALTER TABLE ONLY workflows
    ADD CONSTRAINT workflows_pkey PRIMARY KEY (id);

-- idx_authentication_refresh_session
CREATE INDEX idx_authentication_refresh_session ON authentication_refresh_tokens USING btree (session_id);

-- idx_authentication_sessions_expiry
CREATE INDEX idx_authentication_sessions_expiry ON authentication_sessions USING btree (expires_at);

-- idx_authentication_sessions_user
CREATE INDEX idx_authentication_sessions_user ON authentication_sessions USING btree (user_id);

-- idx_clarifications_request
CREATE INDEX idx_clarifications_request ON clarifications USING btree (request_id, created_at DESC);

-- idx_completion_reconciliation_requests
CREATE INDEX idx_completion_reconciliation_requests ON requests USING btree (id) WHERE (((status)::text = 'APPROVED'::text) AND (execution_plan_id IS NOT NULL) AND (NOT execution_start_unknown));

-- idx_decisions_delegated
CREATE INDEX idx_decisions_delegated ON decisions USING btree (delegated_to) WHERE (delegated_to IS NOT NULL);

-- idx_decisions_request
CREATE INDEX idx_decisions_request ON decisions USING btree (request_id);

-- idx_decisions_step
CREATE INDEX idx_decisions_step ON decisions USING btree (step_instance_id);

-- idx_eligibility_reconciliation_ready
CREATE INDEX idx_eligibility_reconciliation_ready ON eligibility_reconciliation_queue USING btree (available_at, generation);

-- idx_email_log_outbox
CREATE INDEX idx_email_log_outbox ON email_log USING btree (outbox_id);

-- idx_email_log_recipient
CREATE INDEX idx_email_log_recipient ON email_log USING btree (to_address);

-- idx_exec_jobs_request
CREATE INDEX idx_exec_jobs_request ON execution_jobs USING btree (request_id);

-- idx_exec_jobs_status_retry
CREATE INDEX idx_exec_jobs_status_retry ON execution_jobs USING btree (status, next_retry_at) WHERE ((status)::text = ANY (ARRAY[('PLANNED'::character varying)::text, ('FAILED'::character varying)::text]));

-- idx_execution_attempt_events_job
CREATE INDEX idx_execution_attempt_events_job ON execution_attempt_events USING btree (request_id, job_id, sequence_id DESC);

-- idx_execution_attempt_request
CREATE INDEX idx_execution_attempt_request ON execution_attempts USING btree (request_id, job_id, attempt_number DESC);

-- idx_execution_lease
CREATE INDEX idx_execution_lease ON execution_jobs USING btree (lease_expires_at) WHERE ((status)::text = 'STARTED'::text);

-- idx_execution_recovery_job
CREATE INDEX idx_execution_recovery_job ON execution_recovery_commands USING btree (job_id, sequence_id DESC);

-- idx_execution_recovery_request
CREATE INDEX idx_execution_recovery_request ON execution_recovery_commands USING btree (request_id, sequence_id DESC);

-- idx_legacy_completion_reconciliation
CREATE INDEX idx_legacy_completion_reconciliation ON requests USING btree (id) WHERE (((status)::text = 'APPROVED'::text) AND (legacy_plan_disposition_id IS NOT NULL));

-- idx_legacy_plan_dispositions_request
CREATE INDEX idx_legacy_plan_dispositions_request ON legacy_plan_dispositions USING btree (request_id, recorded_at DESC, command_id);

-- idx_lifecycle_audit_request_time
CREATE INDEX idx_lifecycle_audit_request_time ON lifecycle_audit_events USING btree (request_id, occurred_at, sequence_id);

-- idx_notifications_recipient
CREATE INDEX idx_notifications_recipient ON notifications USING btree (recipient_id, created_at DESC);

-- idx_notifications_request
CREATE INDEX idx_notifications_request ON notifications USING btree (request_id);

-- idx_notifications_unread
CREATE INDEX idx_notifications_unread ON notifications USING btree (recipient_id, created_at DESC) WHERE (read_at IS NULL);

-- idx_outbox_recipient
CREATE INDEX idx_outbox_recipient ON outbox USING btree (recipient_id);

-- idx_outbox_status_retry
CREATE INDEX idx_outbox_status_retry ON outbox USING btree (status, next_retry_at) WHERE ((status)::text = ANY ((ARRAY['PENDING'::character varying, 'FAILED'::character varying])::text[]));

-- idx_password_reset_tokens_expires
CREATE INDEX idx_password_reset_tokens_expires ON password_reset_tokens USING btree (expires_at);

-- idx_password_reset_tokens_user
CREATE INDEX idx_password_reset_tokens_user ON password_reset_tokens USING btree (user_id);

-- idx_request_types_dept
CREATE INDEX idx_request_types_dept ON request_types USING btree (owning_dept_id);

-- idx_requests_requester
CREATE INDEX idx_requests_requester ON requests USING btree (requester_id);

-- idx_requests_status
CREATE INDEX idx_requests_status ON requests USING btree (status);

-- idx_requests_type
CREATE INDEX idx_requests_type ON requests USING btree (type_id);

-- idx_role_permissions_perm
CREATE INDEX idx_role_permissions_perm ON role_permissions USING btree (permission_id);

-- idx_step_escalation
CREATE INDEX idx_step_escalation ON step_instances USING btree (next_escalation_at) WHERE (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'BLOCKED'::character varying])::text[])) AND (next_escalation_at IS NOT NULL));

-- idx_step_expiry
CREATE INDEX idx_step_expiry ON step_instances USING btree (expires_at) WHERE (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'BLOCKED'::character varying])::text[])) AND (expires_at IS NOT NULL));

-- idx_step_instances_request
CREATE INDEX idx_step_instances_request ON step_instances USING btree (request_id);

-- idx_step_instances_state
CREATE INDEX idx_step_instances_state ON step_instances USING btree (request_id, state);

-- idx_step_participants_step
CREATE INDEX idx_step_participants_step ON step_participants USING btree (step_instance_id);

-- idx_step_review_work
CREATE INDEX idx_step_review_work ON step_instances USING btree (request_id) WHERE ((state)::text = ANY ((ARRAY['PENDING'::character varying, 'BLOCKED'::character varying])::text[]));

-- idx_user_roles_role_id
CREATE INDEX idx_user_roles_role_id ON user_roles USING btree (role_id);

-- idx_users_active
CREATE INDEX idx_users_active ON users USING btree (active);

-- idx_users_department
CREATE INDEX idx_users_department ON users USING btree (department_id);

-- idx_workflow_steps_wf
CREATE INDEX idx_workflow_steps_wf ON workflow_steps USING btree (workflow_id);

-- permissions_code_scope_ux
CREATE UNIQUE INDEX permissions_code_scope_ux ON permissions USING btree (code, COALESCE(scope_type, ''::character varying), COALESCE(scope_value, ''::character varying));

-- uq_execution_attempt_heartbeat_lease
CREATE UNIQUE INDEX uq_execution_attempt_heartbeat_lease ON execution_attempt_events USING btree (claim_token, lease_expires_at) WHERE ((kind)::text = 'HEARTBEAT'::text);

-- uq_execution_attempt_outcome
CREATE UNIQUE INDEX uq_execution_attempt_outcome ON execution_attempt_events USING btree (claim_token) WHERE ((kind)::text = 'OUTCOME'::text);

-- uq_step_active_request
CREATE UNIQUE INDEX uq_step_active_request ON step_instances USING btree (request_id) WHERE ((state)::text = ANY ((ARRAY['PENDING'::character varying, 'BLOCKED'::character varying])::text[]));

-- uq_step_approval_slot
CREATE UNIQUE INDEX uq_step_approval_slot ON step_approval_votes USING btree (step_instance_id, slot_id) WHERE (slot_id IS NOT NULL);

-- authentication_refresh_tokens authentication_refresh_tokens_session_id_fkey
ALTER TABLE ONLY authentication_refresh_tokens
    ADD CONSTRAINT authentication_refresh_tokens_session_id_fkey FOREIGN KEY (session_id) REFERENCES authentication_sessions(id) ON DELETE CASCADE;

-- authentication_sessions authentication_sessions_user_id_fkey
ALTER TABLE ONLY authentication_sessions
    ADD CONSTRAINT authentication_sessions_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

-- clarifications clarifications_answered_by_fkey
ALTER TABLE ONLY clarifications
    ADD CONSTRAINT clarifications_answered_by_fkey FOREIGN KEY (answered_by) REFERENCES users(id);

-- clarifications clarifications_asked_by_fkey
ALTER TABLE ONLY clarifications
    ADD CONSTRAINT clarifications_asked_by_fkey FOREIGN KEY (asked_by) REFERENCES users(id);

-- clarifications clarifications_request_id_fkey
ALTER TABLE ONLY clarifications
    ADD CONSTRAINT clarifications_request_id_fkey FOREIGN KEY (request_id) REFERENCES requests(id) ON DELETE CASCADE;

-- decisions decisions_actor_id_fkey
ALTER TABLE ONLY decisions
    ADD CONSTRAINT decisions_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES users(id);

-- decisions decisions_delegated_to_fkey
ALTER TABLE ONLY decisions
    ADD CONSTRAINT decisions_delegated_to_fkey FOREIGN KEY (delegated_to) REFERENCES users(id);

-- decisions decisions_request_id_fkey
ALTER TABLE ONLY decisions
    ADD CONSTRAINT decisions_request_id_fkey FOREIGN KEY (request_id) REFERENCES requests(id) ON DELETE CASCADE;

-- decisions decisions_step_instance_id_fkey
ALTER TABLE ONLY decisions
    ADD CONSTRAINT decisions_step_instance_id_fkey FOREIGN KEY (step_instance_id) REFERENCES step_instances(id);

-- execution_attempt_baselines execution_attempt_baselines_job_id_fkey
ALTER TABLE ONLY execution_attempt_baselines
    ADD CONSTRAINT execution_attempt_baselines_job_id_fkey FOREIGN KEY (job_id) REFERENCES execution_jobs(id);

-- execution_attempt_baselines execution_attempt_baselines_request_id_fkey
ALTER TABLE ONLY execution_attempt_baselines
    ADD CONSTRAINT execution_attempt_baselines_request_id_fkey FOREIGN KEY (request_id) REFERENCES requests(id);

-- execution_attempt_events execution_attempt_events_baseline_job_id_fkey
ALTER TABLE ONLY execution_attempt_events
    ADD CONSTRAINT execution_attempt_events_baseline_job_id_fkey FOREIGN KEY (baseline_job_id) REFERENCES execution_attempt_baselines(job_id);

-- execution_attempt_events execution_attempt_events_claim_token_fkey
ALTER TABLE ONLY execution_attempt_events
    ADD CONSTRAINT execution_attempt_events_claim_token_fkey FOREIGN KEY (claim_token) REFERENCES execution_attempts(claim_token);

-- execution_attempt_events execution_attempt_events_job_id_fkey
ALTER TABLE ONLY execution_attempt_events
    ADD CONSTRAINT execution_attempt_events_job_id_fkey FOREIGN KEY (job_id) REFERENCES execution_jobs(id);

-- execution_attempt_events execution_attempt_events_recovery_command_id_fkey
ALTER TABLE ONLY execution_attempt_events
    ADD CONSTRAINT execution_attempt_events_recovery_command_id_fkey FOREIGN KEY (recovery_command_id) REFERENCES execution_recovery_commands(command_id);

-- execution_attempt_events execution_attempt_events_request_id_fkey
ALTER TABLE ONLY execution_attempt_events
    ADD CONSTRAINT execution_attempt_events_request_id_fkey FOREIGN KEY (request_id) REFERENCES requests(id);

-- execution_attempt_provider_contracts execution_attempt_provider_contracts_claim_token_fkey
ALTER TABLE ONLY execution_attempt_provider_contracts
    ADD CONSTRAINT execution_attempt_provider_contracts_claim_token_fkey FOREIGN KEY (claim_token) REFERENCES execution_attempts(claim_token);

-- execution_attempts execution_attempts_job_id_fkey
ALTER TABLE ONLY execution_attempts
    ADD CONSTRAINT execution_attempts_job_id_fkey FOREIGN KEY (job_id) REFERENCES execution_jobs(id);

-- execution_attempts execution_attempts_request_id_fkey
ALTER TABLE ONLY execution_attempts
    ADD CONSTRAINT execution_attempts_request_id_fkey FOREIGN KEY (request_id) REFERENCES requests(id);

-- execution_jobs execution_jobs_cancelled_by_fkey
ALTER TABLE ONLY execution_jobs
    ADD CONSTRAINT execution_jobs_cancelled_by_fkey FOREIGN KEY (cancelled_by) REFERENCES users(id);

-- execution_jobs execution_jobs_request_id_fkey
ALTER TABLE ONLY execution_jobs
    ADD CONSTRAINT execution_jobs_request_id_fkey FOREIGN KEY (request_id) REFERENCES requests(id) ON DELETE CASCADE;

-- execution_plan_actions execution_plan_actions_plan_id_fkey
ALTER TABLE ONLY execution_plan_actions
    ADD CONSTRAINT execution_plan_actions_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES execution_plans(id);

-- execution_plan_job_bindings execution_plan_job_bindings_job_id_fkey
ALTER TABLE ONLY execution_plan_job_bindings
    ADD CONSTRAINT execution_plan_job_bindings_job_id_fkey FOREIGN KEY (job_id) REFERENCES execution_jobs(id);

-- execution_plan_job_bindings execution_plan_job_bindings_plan_id_action_fkey
ALTER TABLE ONLY execution_plan_job_bindings
    ADD CONSTRAINT execution_plan_job_bindings_plan_id_action_fkey FOREIGN KEY (plan_id, action) REFERENCES execution_plan_actions(plan_id, action);

-- execution_plan_materializations execution_plan_materializations_plan_id_action_fkey
ALTER TABLE ONLY execution_plan_materializations
    ADD CONSTRAINT execution_plan_materializations_plan_id_action_fkey FOREIGN KEY (plan_id, action) REFERENCES execution_plan_actions(plan_id, action);

-- execution_plans execution_plans_request_id_fkey
ALTER TABLE ONLY execution_plans
    ADD CONSTRAINT execution_plans_request_id_fkey FOREIGN KEY (request_id) REFERENCES requests(id);

-- execution_plans execution_plans_type_id_fkey
ALTER TABLE ONLY execution_plans
    ADD CONSTRAINT execution_plans_type_id_fkey FOREIGN KEY (type_id) REFERENCES request_types(id);

-- execution_recovery_commands execution_recovery_commands_actor_id_fkey
ALTER TABLE ONLY execution_recovery_commands
    ADD CONSTRAINT execution_recovery_commands_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES users(id);

-- execution_recovery_commands execution_recovery_commands_job_id_fkey
ALTER TABLE ONLY execution_recovery_commands
    ADD CONSTRAINT execution_recovery_commands_job_id_fkey FOREIGN KEY (job_id) REFERENCES execution_jobs(id);

-- execution_recovery_commands execution_recovery_commands_request_id_fkey
ALTER TABLE ONLY execution_recovery_commands
    ADD CONSTRAINT execution_recovery_commands_request_id_fkey FOREIGN KEY (request_id) REFERENCES requests(id);

-- execution_recovery_observations execution_recovery_observations_command_id_fkey
ALTER TABLE ONLY execution_recovery_observations
    ADD CONSTRAINT execution_recovery_observations_command_id_fkey FOREIGN KEY (command_id) REFERENCES execution_recovery_commands(command_id);

-- execution_recovery_provider_contracts execution_recovery_provider_contracts_command_id_fkey
ALTER TABLE ONLY execution_recovery_provider_contracts
    ADD CONSTRAINT execution_recovery_provider_contracts_command_id_fkey FOREIGN KEY (command_id) REFERENCES execution_recovery_commands(command_id);

-- request_types fk_request_types_default_workflow
ALTER TABLE ONLY request_types
    ADD CONSTRAINT fk_request_types_default_workflow FOREIGN KEY (default_workflow_id) REFERENCES workflows(id);

-- legacy_plan_dispositions legacy_plan_dispositions_actor_id_fkey
ALTER TABLE ONLY legacy_plan_dispositions
    ADD CONSTRAINT legacy_plan_dispositions_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES users(id);

-- legacy_plan_dispositions legacy_plan_dispositions_plan_id_fkey
ALTER TABLE ONLY legacy_plan_dispositions
    ADD CONSTRAINT legacy_plan_dispositions_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES execution_plans(id) DEFERRABLE INITIALLY DEFERRED;

-- legacy_plan_dispositions legacy_plan_dispositions_request_id_fkey
ALTER TABLE ONLY legacy_plan_dispositions
    ADD CONSTRAINT legacy_plan_dispositions_request_id_fkey FOREIGN KEY (request_id) REFERENCES requests(id);

-- legacy_plan_provider_bindings legacy_plan_provider_bindings_disposition_id_fkey
ALTER TABLE ONLY legacy_plan_provider_bindings
    ADD CONSTRAINT legacy_plan_provider_bindings_disposition_id_fkey FOREIGN KEY (disposition_id) REFERENCES legacy_plan_dispositions(command_id);

-- legacy_plan_provider_bindings legacy_plan_provider_bindings_job_id_fkey
ALTER TABLE ONLY legacy_plan_provider_bindings
    ADD CONSTRAINT legacy_plan_provider_bindings_job_id_fkey FOREIGN KEY (job_id) REFERENCES execution_jobs(id);

-- lifecycle_audit_events lifecycle_audit_events_request_id_fkey
ALTER TABLE ONLY lifecycle_audit_events
    ADD CONSTRAINT lifecycle_audit_events_request_id_fkey FOREIGN KEY (request_id) REFERENCES requests(id);

-- password_reset_tokens password_reset_tokens_user_id_fkey
ALTER TABLE ONLY password_reset_tokens
    ADD CONSTRAINT password_reset_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

-- request_audit_coverage request_audit_coverage_request_id_fkey
ALTER TABLE ONLY request_audit_coverage
    ADD CONSTRAINT request_audit_coverage_request_id_fkey FOREIGN KEY (request_id) REFERENCES requests(id);

-- request_types request_types_owning_dept_id_fkey
ALTER TABLE ONLY request_types
    ADD CONSTRAINT request_types_owning_dept_id_fkey FOREIGN KEY (owning_dept_id) REFERENCES departments(id);

-- requests requests_execution_plan_id_fkey
ALTER TABLE ONLY requests
    ADD CONSTRAINT requests_execution_plan_id_fkey FOREIGN KEY (execution_plan_id) REFERENCES execution_plans(id);

-- requests requests_legacy_plan_disposition_id_fkey
ALTER TABLE ONLY requests
    ADD CONSTRAINT requests_legacy_plan_disposition_id_fkey FOREIGN KEY (legacy_plan_disposition_id) REFERENCES legacy_plan_dispositions(command_id);

-- requests requests_requester_id_fkey
ALTER TABLE ONLY requests
    ADD CONSTRAINT requests_requester_id_fkey FOREIGN KEY (requester_id) REFERENCES users(id);

-- requests requests_type_id_fkey
ALTER TABLE ONLY requests
    ADD CONSTRAINT requests_type_id_fkey FOREIGN KEY (type_id) REFERENCES request_types(id);

-- role_permissions role_permissions_permission_id_fkey
ALTER TABLE ONLY role_permissions
    ADD CONSTRAINT role_permissions_permission_id_fkey FOREIGN KEY (permission_id) REFERENCES permissions(id) ON DELETE CASCADE;

-- role_permissions role_permissions_role_id_fkey
ALTER TABLE ONLY role_permissions
    ADD CONSTRAINT role_permissions_role_id_fkey FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE;

-- step_approval_votes step_approval_votes_actor_id_fkey
ALTER TABLE ONLY step_approval_votes
    ADD CONSTRAINT step_approval_votes_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES users(id);

-- step_approval_votes step_approval_votes_decision_id_fkey
ALTER TABLE ONLY step_approval_votes
    ADD CONSTRAINT step_approval_votes_decision_id_fkey FOREIGN KEY (decision_id) REFERENCES decisions(id);

-- step_approval_votes step_approval_votes_slot_id_fkey
ALTER TABLE ONLY step_approval_votes
    ADD CONSTRAINT step_approval_votes_slot_id_fkey FOREIGN KEY (slot_id) REFERENCES step_participants(id);

-- step_approval_votes step_approval_votes_step_instance_id_fkey
ALTER TABLE ONLY step_approval_votes
    ADD CONSTRAINT step_approval_votes_step_instance_id_fkey FOREIGN KEY (step_instance_id) REFERENCES step_instances(id);

-- step_instances step_instances_recovery_owner_id_fkey
ALTER TABLE ONLY step_instances
    ADD CONSTRAINT step_instances_recovery_owner_id_fkey FOREIGN KEY (recovery_owner_id) REFERENCES users(id);

-- step_instances step_instances_request_id_fkey
ALTER TABLE ONLY step_instances
    ADD CONSTRAINT step_instances_request_id_fkey FOREIGN KEY (request_id) REFERENCES requests(id) ON DELETE CASCADE;

-- step_instances step_instances_satisfied_by_fkey
ALTER TABLE ONLY step_instances
    ADD CONSTRAINT step_instances_satisfied_by_fkey FOREIGN KEY (satisfied_by) REFERENCES users(id);

-- step_instances step_instances_step_id_fkey
ALTER TABLE ONLY step_instances
    ADD CONSTRAINT step_instances_step_id_fkey FOREIGN KEY (step_id) REFERENCES workflow_steps(id);

-- step_participants step_participants_decision_id_fkey
ALTER TABLE ONLY step_participants
    ADD CONSTRAINT step_participants_decision_id_fkey FOREIGN KEY (decision_id) REFERENCES decisions(id);

-- step_participants step_participants_predecessor_id_step_instance_id_slot_id_fkey
ALTER TABLE ONLY step_participants
    ADD CONSTRAINT step_participants_predecessor_id_step_instance_id_slot_id_fkey FOREIGN KEY (predecessor_id, step_instance_id, slot_id) REFERENCES step_participants(id, step_instance_id, slot_id);

-- step_participants step_participants_recovery_id_fkey
ALTER TABLE ONLY step_participants
    ADD CONSTRAINT step_participants_recovery_id_fkey FOREIGN KEY (recovery_id) REFERENCES step_recoveries(id);

-- step_participants step_participants_slot_id_fkey
ALTER TABLE ONLY step_participants
    ADD CONSTRAINT step_participants_slot_id_fkey FOREIGN KEY (slot_id) REFERENCES step_participants(id);

-- step_participants step_participants_step_instance_id_fkey
ALTER TABLE ONLY step_participants
    ADD CONSTRAINT step_participants_step_instance_id_fkey FOREIGN KEY (step_instance_id) REFERENCES step_instances(id);

-- step_participants step_participants_user_id_fkey
ALTER TABLE ONLY step_participants
    ADD CONSTRAINT step_participants_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id);

-- step_recoveries step_recoveries_actor_id_fkey
ALTER TABLE ONLY step_recoveries
    ADD CONSTRAINT step_recoveries_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES users(id);

-- step_recoveries step_recoveries_step_instance_id_fkey
ALTER TABLE ONLY step_recoveries
    ADD CONSTRAINT step_recoveries_step_instance_id_fkey FOREIGN KEY (step_instance_id) REFERENCES step_instances(id);

-- user_roles user_roles_role_id_fkey
ALTER TABLE ONLY user_roles
    ADD CONSTRAINT user_roles_role_id_fkey FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE;

-- user_roles user_roles_user_id_fkey
ALTER TABLE ONLY user_roles
    ADD CONSTRAINT user_roles_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

-- users users_department_id_fkey
ALTER TABLE ONLY users
    ADD CONSTRAINT users_department_id_fkey FOREIGN KEY (department_id) REFERENCES departments(id);

-- workflow_steps workflow_steps_workflow_id_fkey
ALTER TABLE ONLY workflow_steps
    ADD CONSTRAINT workflow_steps_workflow_id_fkey FOREIGN KEY (workflow_id) REFERENCES workflows(id) ON DELETE CASCADE;
