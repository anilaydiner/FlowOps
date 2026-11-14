# FlowOps — Implementation Plan

**Centralized Request & Approval Platform** (Qimia Enterprise requirements v1.0)
Stack adapted per decision: **Spring Boot 4.1 + Java 21 + PostgreSQL** (replacing .NET 10 / MSSQL from the original doc).

---

## 1. Tech Stack

| Concern            | Choice                                              | Notes |
|--------------------|-----------------------------------------------------|-------|
| Language/Runtime   | Java 21 (Corretto)                                  | Already installed |
| Framework          | Spring Boot 4.1.1 (existing scaffold)               | `com.example.flowops` → rename to `com.flowops` |
| Database           | PostgreSQL 16+                                      | Local: Docker `postgres:16-alpine`; tests: Testcontainers |
| Data access        | Spring Data JPA + Hibernate 7                       | Optimistic locking for concurrency safety |
| Migrations         | Flyway (versioned SQL)                              | `src/main/resources/db/migration/V*__*.sql` |
| Security           | Spring Security, stateless JWT                      | `/api/v1/auth/token` issues JWT; roles + scopes in claims/DB |
| Validation         | spring-boot-starter-validation + RFC 7807           | ProblemDetails (Spring built-in) |
| Background work    | Spring `@Scheduled` pollers + `FOR UPDATE SKIP LOCKED` | Outbox dispatcher, job worker, SLA scanner |
| Observability      | Micrometer + OpenTelemetry, JSON logging (logstash-encoder), MDC correlation ID | `X-Correlation-Id` in → MDC → logs/audit/traces |
| Testing            | JUnit 5, Testcontainers (PostgreSQL), MockMvc, stepwise acceptance scenarios | |

## 2. Architecture (single modular monolith, API-first)

```
com.flowops
├── FlowOpsApplication
├── api/            # REST controllers, DTOs, request validation, ProblemDetails mapping
├── domain/         # Entities, enums (RequestStatus, StepState, OutboxStatus, JobStatus)
├── service/
│   ├── request/    # RequestService: draft/edit/submit/cancel
│   ├── workflow/   # WorkflowEngine: step evaluation, completion rules, conditions, blocked state
│   ├── authz/      # AuthorizationEvaluator: RBAC + scoped policies + explainable decisions
│   ├── outbox/     # OutboxService (transactional writes) + OutboxDispatcher (async delivery)
│   ├── execution/  # ExecutionService (job enqueue) + JobWorker (claim/execute/retry)
│   ├── audit/      # AuditService (append-only events, before/after snapshots)
│   └── admin/      # Users, roles, permission catalog, request types, workflows
├── security/       # JWT filter, CurrentUser, scope enforcement, SoD checks
├── infra/          # Config, correlation filter, JSON logging, metrics, OTel wiring
└── migration/      # (Flyway SQL under resources/db/migration)
```

**Cross-cutting rules**
- Every state mutation happens inside one DB transaction: state change + outbox entry + audit event commit atomically.
- All side effects (email, feed, provisioning, tickets, accounting) go through the **outbox table** — never inline.
- Optimistic lock (`@Version`) on `requests`; a transition that loses the race re-reads and rejects with 409.
- Audit table is append-only (no update/delete endpoints; DB role without UPDATE/DELETE privileges).

## 3. Data Model (core tables)

| Table | Purpose |
|---|---|
| `users` | id, username, display_name, dept_id, email, active |
| `departments` | id, code, name (Finance, IT, HR, Operations) |
| `permissions` | Atomic catalog: e.g. `request:create`, `request:approve`, `audit:read:any` + scope type |
| `roles`, `role_permissions`, `user_roles` | RBAC; effective permissions = union of roles |
| `request_types` | code, name, owning_dept, default_workflow_id, required_fields (JSONB), validation rules (JSONB) |
| `workflows`, `workflow_steps` | ordered steps: position, role/eligibility, condition (JSONB), completion rule (`ANY_OF`/`ALL_OF`/`K_OF_N`, k), SLA minutes, escalation target, is_terminal |
| `requests` | id, type, requester_id, status, payload (JSONB), version, correlation_id, timestamps |
| `step_instances` | request_id, step_id, state (PENDING/SATISFIED/SKIPPED/BLOCKED), rule, decided_by/at, reason, skip_reason |
| `decisions` | request_id, step_instance_id, actor, action (APPROVE/REJECT/DELEGATE/OVERRIDE), justification, evidence (JSONB) |
| `outbox` | id, kind (NOTIFICATION/JOB), payload (JSONB), idempotency_key (UNIQUE), status, attempts, next_retry_at, correlation_id |
| `notifications` | delivery feed entries: request_id, target_user, channel, status, sent_at |
| `execution_jobs` | request_id, action, status (PLANNED/STARTED/COMPLETED/FAILED), attempts, idempotency_key, result |
| `audit_events` | immutable: ts, actor_id, action, entity_type/id, before (JSONB), after (JSONB), ip, user_agent, correlation_id, reason |
| `clarifications` | request_id, asked_by, question, answered_by, answer, timestamps (iteration 7) |

## 4. API Surface (v1, all under `/api/v1`)

```
POST   /auth/token                          # get JWT (training-grade login)
GET    /users, POST /users, PUT/DELETE      # admin
GET    /permissions                         # catalog (read)
POST   /roles, PUT/DELETE /roles/{id}       # admin, role = set of permissions
GET    /request-types, POST/PUT             # admin, extensible types
GET    /workflows, POST/PUT                 # admin
GET    /departments                         # read-only list (implemented, iteration 1)
POST   /requests                            # create draft (validates per type)
PUT    /requests/{id}                       # draft edits only
POST   /requests/{id}/submit                # starts workflow + outbox notification (atomic)
POST   /requests/{id}/cancel
GET    /requests?status=&type=&mine=        # list (scoped)
GET    /requests/{id}                       # detail incl. steps + decisions
POST   /requests/{id}/decisions             # APPROVE/REJECT/DELEGATE/OVERRIDE + justification
POST   /requests/{id}/close                 # EXECUTED|REJECTED → CLOSED (terminal)
POST   /requests/{id}/clarifications        # ask clarification (iteration 7)
PUT    /requests/{id}/clarifications/{cid}  # respond to clarification (iteration 7)
GET    /queue                               # my pending approvals (step-eligible)
GET    /audit?from=&to=&entity=             # requires audit:read:any
GET    /notifications                       # internal feed
GET    /actuator/health, /actuator/metrics  # observability
```

Consistent error format: RFC 7807 ProblemDetails with stable `type` URIs and `correlationId` extension.

## 5. Request Lifecycle (state machine)

`RequestStatus` (per PDF §6.1) — every request row carries one of:

```
DRAFT ──submit──▶ SUBMITTED ──▶ IN_REVIEW ──all steps approved──▶ APPROVED ──jobs done──▶ EXECUTED ──close──▶ CLOSED
  │                  │               │                              │
  └────cancel────────┴────cancel─────┴──reject──▶ REJECTED ──────────┴──close──▶ CLOSED
                                        │  (no jobs; EXECUTED skipped)
                          SLA expiry ────▶ EXPIRED (terminal, set by SLA scanner)
```

| State | Driver |
|---|---|
| `DRAFT` | create; editable via `PUT`; `cancel` allowed |
| `SUBMITTED` | transient inside the submit transaction (state + step instances + outbox commit atomically); not observable via API on success |
| `IN_REVIEW` | active while any step is unsatisfied; decisions, delegate, override act here |
| `APPROVED` | last step satisfied; execution jobs enqueued (if the type has them) |
| `EXECUTED` | **all** execution jobs for the request COMPLETED (or type has none → straight to APPROVED→EXECUTED on approval); set by job worker (iteration 7) |
| `CLOSED` | terminal; explicit `POST /requests/{id}/close` from `EXECUTED` or `REJECTED` |
| `REJECTED` | terminal-alternative; any step's final decision REJECT |
| `CANCELLED` | terminal; requester (or admin) cancels any non-terminal state |
| `EXPIRED` | terminal; SLA scanner marks long-pending requests beyond the configured expiry window (iteration 7) |

- `BLOCKED` (no eligible approver, §6.4) is a **step-level condition**, not a `RequestStatus` value — the request stays `IN_REVIEW` with the blocked step visible in the API.
- Transitions guarded by optimistic locking (§2); every transition writes an audit event (§9).
- **Assumption (not explicit in PDF):** `CLOSED` semantics (explicit close after EXECUTED/REJECTED) and `EXPIRED` (scanner-driven) — PDF §6.1 lists both as lifecycle states without defining drivers.

## 6. Workflow Engine Semantics

1. `submit` → engine loads the type's default workflow, materializes `step_instances` for the **first** step; conditional steps resolved at decision time; skipped steps recorded as `SKIPPED (Not Applicable)`.
2. Step completion rules: `ANY_OF(1)`, `ALL_OF(n)`, `K_OF_N(k)`. Step advances when rule satisfied.
3. Conditions are JSONB expressions evaluated against `requests.payload` at decision time — e.g. Expense: `amount > 5000 → require Finance step`.
4. **Blocked** state: no eligible approver for a required step → request `BLOCKED`, admin notification via outbox, visible in API.
5. Concurrency: transition guarded by `status = expected AND version = expected`; duplicate approve by 2 actors → one wins, the other gets 409; outbox idempotency keys guarantee single side-effect.
6. Separation of duties: default deny if `requester == approver`; `OVERRIDE` action requires explicit `request:override` permission + mandatory justification (audited).

## 7. Authorization Model (RBAC + Scopes)

- `AuthorizationEvaluator.authorize(user, permission, targetContext) → Decision{allowed, evidence[]}`
- Scope types: `SELF`, `DEPARTMENT`, `REQUEST_TYPE`, `STEP_ELIGIBILITY`, `THRESHOLD` (e.g. amount ≤ 1000)
- A user **without a department** can never satisfy a `DEPARTMENT` scope (default deny) — e.g. departmentless users cannot approve department-scoped requests.
- Evidence array = which permission, which role, which scope matched → stored in `decisions.evidence` and `audit_events` → explainable + auditable.
- Enforcement points: controller guard (coarse permission) + engine guard (fine scope/eligibility).

## 8. Reliability (Outbox + Jobs)

- **Outbox writer**: in the same transaction as business state change.
- **Notification triggers (PDF §8.1)** — all via outbox, never inline:
  - Submit → notify manager / first approver(s)
  - Approve / reject → notify requester
  - SLA breach → notify escalation targets
  - Channels: email (simulated) + internal feed (`GET /notifications`)
- **Dispatcher** (poller, every few seconds): claims batch with `FOR UPDATE SKIP LOCKED`, delivers:
  - Email → simulated `EmailGateway` (log + store), retry with exponential backoff (max 5, then `DEAD_LETTER` + alert)
  - Feed → insert into `notifications`
- **Job worker**: claims execution jobs the same way; each action handler is **idempotent** (keyed on request id + action); retries with backoff; states PLANNED→STARTED→COMPLETED/FAILED(→retried). When **all** jobs of a request are COMPLETED → request transitions `APPROVED`→`EXECUTED` (§5); audit actor is `system`.
- **SLA scanner**: periodic; step pending beyond SLA → escalation notification via outbox; pending beyond the configured expiry window → request `EXPIRED` (terminal, §5), audit actor `system`.

## 9. Audit & Observability

- Audit events written transactionally for: state transitions, decisions (with justification + evidence), delegation, clarification, job lifecycle, admin changes (roles/permissions/workflows/request types).
- Captures: who, what, when, where (IP + user-agent), correlation ID, before/after JSONB snapshots.
- Correlation ID: inbound header or generated UUID → MDC → JSON log field → outbox/audit rows → OTel span attribute.
- Metrics: `flowops_requests_total`, `flowops_approval_latency_seconds`, `flowops_outbox_backlog`, `flowops_execution_backlog`, `flowops_dispatch_failures_total`, `flowops_execution_failures_total`.
- System-driven events (job completion → `EXECUTED`, SLA expiry → `EXPIRED`) are audited with actor `system` — the PDF's "System Executor" stakeholder surfaces as this actor, not a login.
- Structured JSON logging; sensitive fields (receipts, justifications) redacted from logs.

## 10. Iteration Plan (mapped from requirements §13)

| # | Iteration | Deliverables | Acceptance proof |
|---|-----------|--------------|------------------|
| 1 ✅ | Foundations + User Management | Project setup, PG via Docker + Flyway, JWT auth, users + departments CRUD/read (seed + `GET /departments`), correlation ID, JSON logs, ProblemDetails, health | `POST /auth/token` → CRUD users; logs carry `correlationId` |
| 2 ◐ | Permission Catalog + Roles | permissions table seeded ✅, roles/permissions CRUD ✅, catalog-based permission guard, effective-permission union | Scenario 7: admin creates role from catalog, no deploy |
| 3 ✅ | Request Types + Draft Lifecycle | request_types + JSONB fields, 4 seed types (Expense, IT Access, Equipment, Policy Exception) with PDF §5.1 owning depts (seed `PROCUREMENT`, `COMPLIANCE`), IT Access `duration` **required** (time-bound), draft create/edit/cancel, per-type validation | Draft CRUD per type with validation errors (ProblemDetails) — 58/58 tests green (2026-11-14) |
| 4 ✅ | Submit + Workflow Engine v1 | full `RequestStatus` machine (§5: DRAFT→…→CLOSED incl. EXECUTED/CLOSED/EXPIRED), step_instances, completion rules, conditional steps (threshold), Blocked state, `POST /requests/{id}/close`, optimistic-lock concurrency | Scenarios 1, 2, 3 (incl. simultaneous-approve test) — 79/79 tests green (2026-11-14) |
| 5 ✅ | Scoped Authorization + Eligibility | SELF/DEPT/TYPE/STEP/THRESHOLD scopes, explainable decisions, SoD + override, evidence in `decisions.evidence` JSONB, `AuthorizationEvaluator` service, `ScopeType` enum, `TargetContext`, permission prefix matching | Scenarios 5, 6; evidence visible in API response — **89/89 tests green (2026-11-14)** |
| 6 ✅ | Outbox + Notifications | outbox table, dispatcher, all 3 triggers (§8: submit→approver, approve/reject→requester, SLA→escalation), simulated email with retry/backoff, feed endpoint | Scenarios 1 + 4: approval notifies requester; email gateway "down" → submit succeeds → delivery after recovery — **104/104 tests green (2026-11-14)** |
| 7 ✅ | Execution Jobs + SLA + Operator Actions | job worker, idempotent actions (provisioning/purchase ticket/accounting export), all-jobs-done → `EXECUTED` (§5), SLA breach + escalation + `EXPIRED`, delegate action, clarifications (table + endpoints, audit actor incl. `system`) | Job lifecycle visible in API; duplicate trigger fires once; request reaches `EXECUTED` — **122/122 tests green (2026-11-14)** |
| 8 ✅ | Audit + Observability + Hardening | audit queries + immutability, OTel tracing, metrics, failure drills (kill dispatcher, duplicate submissions), redaction check | Full audit trail queryable; metrics exposed; drills pass — **158/158 tests green (2026-11-14)** |

### Iteration 1 — Completed (2026-11-14)

All 18 tests green (`./mvnw test`), app verified live on `:8080`. Departments are part of this iteration: seeded in `V1__initial.sql` (FINANCE/IT/HR/OPERATIONS) with `GET /api/v1/departments` (read-only, filter/sort/pagination). Key Boot 4.1 / Spring 7 specifics learned (apply to later iterations):

- **Jackson 3** (`tools.jackson.*`): the managed `ObjectMapper` bean is a `JsonMapper` (subclass of `tools.jackson.databind.ObjectMapper`) carrying Boot's `ProblemDetailJacksonMixin` (`@JsonAnyGetter` on `getProperties()` → RFC 7807 top-level properties). Always inject this mapper — a bare `new ObjectMapper()` serializes ProblemDetail properties nested/incorrectly (bit us in the 401/403 security handlers).
- **Structured logging**: Boot 3.x's `spring.structured-logging.*` is gone. Use `logging.structured.format.console|file: logstash` (format ids: `ecs`, `gelf`, `logstash`; `logstash` = JSON). MDC entries are written as top-level JSON members. Correlation ID flows: `X-Correlation-Id` header → MDC `correlationId` → JSON log member + ProblemDetail `correlationId` property + response header.
- **ProblemDetails**: Boot backs off from its built-in `ProblemDetailsExceptionHandler` when any `ResponseEntityExceptionHandler` bean exists → `FlowOpsExceptionHandler` (extends it) owns 400s, adding per-field `violations` + `correlationId`. Spring 7 builds validation bodies via the exception's own `ErrorResponse` payload, so override `handleMethodArgumentNotValid(...)` directly (`createProblemDetail` is not consulted for ErrorResponse exceptions).
- **Docker 29 + Testcontainers**: requires `DOCKER_API_VERSION=1.44` (path-style API ≥1.40), set in surefire + `~/.docker-java.properties`.
- **Flyway** is modularized: `spring-boot-starter-flyway` (+ `flyway-database-postgresql`).
- MockMvc lives in the new `spring-boot-starter-webmvc-test` artifact (`org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`).

### Iteration 2 — In progress

- **Done**: `V2__rbac.sql` (roles, permission catalog, `user_roles`, `role_permissions`, ADMIN/MEMBER seeds incl. `departments:read`); `RoleController` + `PermissionController` (CRUD + filtered list, role = set of permissions from catalog).
- **Remaining**: catalog-based permission guard (today `SecurityConfig` hardcodes `hasRole("ADMIN")` — must enforce seeded permission codes), effective-permission union (user → roles → permissions) for the `AuthorizationEvaluator` (iteration 5), Scenario 7 acceptance proof.

### Iteration 3 — Completed (2026-11-14)

**58/58 tests green** (`./mvnw test`), single stable Testcontainers DB for the whole suite.

- **Schema** (`V3__request_types.sql`): seeds `PROCUREMENT` + `COMPLIANCE` departments; `request_types` (code, name, owning dept, `required_fields` + `validation_rules` as JSONB) with 4 seed types — `EXPENSE` (amount ≥ 1), `IT_ACCESS` (`duration` **required**, pattern `^[0-9]+(d|days|w|weeks|m|months)$`), `EQUIPMENT`, `POLICY_EXCEPTION`; `requests` (type, requester, status default `DRAFT`, JSONB payload, `@Version` optimistic lock, correlation id).
- **Domain**: `RequestStatus` (9 states + `isTerminal()`), `RequestType`, `Request` (`newDraft`/`replacePayload`/`cancel`/`isDraft`), repos + Specifications (non-admins always scoped to own requests).
- **Validation**: `PayloadValidator` driven entirely by the type's JSONB — required fields (present + non-blank), number type/min, regex pattern → `RequestValidationException` → 400 ProblemDetails with per-field `violations`.
- **Lifecycle**: create/edit/cancel drafts; edit re-validates; ownership enforced — non-owner gets **404** on read, edit, and cancel (no existence leak); `RequestService.ready()` initializes lazy associations (requester, owning dept) inside the transaction so controller-side DTO mapping cannot hit `LazyInitializationException`.
- **API**: `RequestTypeController` (CRUD, sieve: code/name/departmentCode/active) + `RequestController` (POST draft, GET list w/ status+typeCode+mine filters, GET/PUT/{id}, POST/{id}/cancel); writes on request-types → ADMIN.
- **Test infra fix**: Testcontainers `@Container` on a static field restarted the Postgres container (new port) between test classes while the cached Spring context kept the old datasource → mass connection-refused. Fix: start the container in a static initializer (`AbstractIntegrationTest`), owned by the JVM lifetime; Testcontainers shutdown hook still cleans up.

### Iteration 4 — Completed (2026-11-14)

**79/79 tests green** (`./mvnw test`), single stable Testcontainers DB for the whole suite.

- **Schema** (`V4__workflows.sql`): `workflows` (code, name, description, active), `workflow_steps` (position, code, name, required_role, completion_rule, required_approvals, condition JSONB, sla_minutes, escalation_role, terminal), `step_instances` (request, workflow_step, state, skip_reason, blocked_reason, satisfied_by, satisfied_at), `decisions` (request, step_instance, actor, action, justification, evidence JSONB). Seeds 4 workflows (WF_EXPENSE with conditional Finance Review when amount>5000, WF_IT_ACCESS, WF_EQUIPMENT, WF_POLICY_EXCEPTION). Links each request type to its workflow via `default_workflow_id`.
- **Domain**: `CompletionRule` (ANY_OF/ALL_OF/K_OF_N), `StepState` (PENDING/SATISFIED/SKIPPED/BLOCKED), `DecisionAction` (APPROVE/REJECT/DELEGATE/OVERRIDE), `Workflow`, `WorkflowStep` (JSONB condition), `StepInstance` (skip/block/satisfy), `Decision` (immutable), repos. `Request` updated: `transitionTo()`, `markSubmitted()`, transient `loadedSteps`/`loadedDecisions` for detail views. `RequestType` updated: `defaultWorkflow` association. `UserRepository` updated: `countActiveByRoleCode(role, excludeId)` for SoD-aware eligibility probe.
- **Engine** (`WorkflowEngine`): `submit()` DRAFT→IN_REVIEW, materializes steps via `materializeFrom()` (conditional steps evaluated by `ConditionEvaluator`, blocked steps detected via eligibility probe). `decide()` APPROVE/REJECT with SoD (requester≠approver), role check, completion rule (count APPROVE decisions vs required_approvals), advance or reject. `close()` EXECUTED|REJECTED→CLOSED. Every mutation commits through the request's `@Version` guard → concurrent decisions serialize, loser gets 409.
- **Service**: `WorkflowService` (CRUD, `replaceSteps()`, ANY_OF forces required_approvals=1), `RequestService` extended with `submit()`/`decide()`/`close()` calling the engine; `ready()` (light, for lists) vs `withDetails()` (loads steps+decisions for detail path).
- **API**: `WorkflowController` (CRUD + sieve list: code/name filters, sort whitelist). `RequestController` extended: `POST /{id}/submit`, `POST /{id}/decisions` (action + justification), `POST /{id}/close`. `RequestResponse` extended with `steps[]` (each containing `decisions[]`). `DecisionRequest` DTO (action @NotNull, justification @Size). `WorkflowRequest`/`WorkflowResponse` DTOs.
- **Security**: workflow write rules (POST/PUT/DELETE `/api/v1/workflows` → ADMIN).
- **Tests**: `WorkflowApiTest` (21 tests: seeded workflows, CRUD, submit, conditional steps, approval flow, SoD, blocked step, multi-approval ALL_OF, close). `ConcurrentDecideTest` (1 test: 10 threads race on the same step via service layer — verifies `ObjectOptimisticLockingFailureException`). `RequestsApiTest` updated (2 tests relaxed to not assert exact counts — shared DB across test classes).
- **Boot 4.1 specifics**: TestRestTemplate autoconfig broken (references `RestTemplateBuilder` from `spring-boot-starter-restclient` which isn't on classpath). Concurrency test uses JDK `HttpClient` for HTTP setup + direct service injection for the race. `ResponseEntity.getStatusCode()` returns `HttpStatusCode` (not `HttpStatus`). Jackson 3 (`tools.jackson` namespace). Surefire needs `DOCKER_API_VERSION=1.44`.

### Iteration 6 — Completed (2026-11-14)

**104/104 tests green** (`./mvnw test`), single stable Testcontainers DB for the whole suite.

- **Design** (confirmed with user): single-app **Transactional Outbox** — no separate service/broker. Outbox rows are written in the *same* `@Transactional` as the business state change; an in-process `@Scheduled` poller (2 s) claims and delivers.
- **Schema** (`V7__outbox_notifications.sql`): `outbox` (idempotency_key UNIQUE, channel, recipient, JSONB payload, status PENDING/SENT/FAILED/DEAD_LETTER, retry_count, max_retries=5, next_retry_at, last_error, correlation_id, sent_at; partial index `WHERE status IN ('PENDING','FAILED')`), `notifications` (recipient, type, title, body, request_id, read_at; partial index `WHERE read_at IS NULL`), `email_log` (simulated delivery store).
- **Domain**: `OutboxMessage` (`markSent`/`markFailed`/`canRetry`; `@JdbcTypeCode(SqlTypes.JSON)` on String payload), `Notification` (`markRead`), `EmailLogEntry`; repos — `claimBatch()` native query with `FOR UPDATE SKIP LOCKED` + `retry_count < max_retries` + due-window, `findByIdempotencyKey`, `countByStatus`.
- **OutboxService**: idempotent `notifyFeed` / `notifyEmail` / `notifySlaBreach` (key = `req:{id}:{type}:{recipient}`); pre-check + `DataIntegrityViolationException` swallow for concurrent duplicates; manual `toJson` (avoids Jackson version coupling).
- **OutboxDispatcher**: `scheduledTick()` (2 s, gated by `running` flag — tests set `running=false` and call `dispatch()` manually for determinism); exponential backoff `5s · 2^retryCount`; 5 failures → `DEAD_LETTER`; EMAIL → `SimulatedEmailGateway.send()` + `email_log` row (recipients **without an email are skipped + logged**, not retried into the dead letter); FEED → `notifications` insert.
- **Triggers** (`WorkflowEngine`, PLAN §8.1): submit → requester (FEED `NOTIFY_SUBMIT` + EMAIL `SUBMITTED`); REJECT → requester (FEED `NOTIFY_REJECT` + EMAIL `REJECTED`); full APPROVE → requester (FEED `NOTIFY_APPROVE` + EMAIL `APPROVED`). SLA escalation trigger arrives with iteration 7.
- **API**: `NotificationController` at `/api/v1/notifications` — `GET` (list, `unread` filter, pagination), `GET /unread-count`, `PATCH /{id}/read`, `PATCH /read-all`; ownership enforced (404 for foreign notifications); 401 unauthenticated.
- **Tests** (`OutboxNotificationsTest`, 15): outbox creation on submit/approve/reject; dispatcher delivery (feed + email_log); retry/backoff + dead-letter (gateway failure injection, `retryCount` 1→5, backoff 5s→80s verified in logs); idempotency; feed endpoint; security.
- **Bug fixed (concurrency)**: losing thread in a version race previously hit the outbox **unique-constraint violation at flush time** (Hibernate runs INSERTs before UPDATEs) *before* the optimistic-lock UPDATE could fail → masked as `DataIntegrityViolationException`/`AssertionFailure`, breaking `ConcurrentDecideTest`. Fix: `requests.flush()` right after `requests.save(...)` and **before** the outbox writes in `submit()`/`decide()` → losers now fail on the version guard with a proper `ObjectOptimisticLockingFailureException` (→ 409) and never touch the outbox.
- **Boot 4.1 / Hibernate 7 gotchas**: JSONB read-back of a String payload is **re-serialized with sorted keys + spaces** (`"requestId": 7`) — never string-match the stored payload, parse it; `repository.flush()` is a no-op outside a transaction (use `saveAll` to persist test-side mutations).

### Iteration 8 — Completed (2026-11-14)

**158/158 tests green** (`./mvnw test`), single stable Testcontainers DB for the whole suite.

- **Audit trail** (`AuditService`, `GET /api/v1/audit`): queryable timeline per request — STATUS transitions, SUBMIT, DECISION (with justification + evidence), JOB lifecycle, SLA breach events (sourced from outbox by idempotency-key prefix). Type filter, date range, entity filter. `AuditApiTest` (6 tests).
- **Audit immutability** (`V9__audit_immutability.sql`): PostgreSQL triggers block all `UPDATE`/`DELETE` on `decisions` (append-only), `execution_jobs` (identity columns immutable, lifecycle columns mutable), `clarifications` (identity immutable, answer mutable). `ImmutabilityTest` (5 tests) verifies each trigger fires correctly and that legitimate lifecycle updates (status, completed_at, answer) still work.
- **OTel tracing** (`FlowOpsSpans`, `InMemorySpanExporter`): spans per request-lifecycle event — `request.submit`, `request.decide`, `job.execute`, `outbox.deliver`, `sla.scan`. Spans carry `request.id`, `user`, `action`, `outbox.key` attributes. Failures produce `StatusCode.ERROR` spans. `ObservabilityTest` span assertions (3 tests).
- **Metrics** (Micrometer): counters — `flowops.request.submitted/decided/approved`, `flowops.outbox.message.sent/failed`, `flowops.job.completed/failed/dead_letter`, `flowops.sla.breach`; gauges — `flowops.outbox.pending`, `flowops.job.pending`; timer — `flowops.outbox.dispatch`. Tagged by `action`/`channel`/`step`. `ObservabilityTest` metric assertions (4 tests).
- **Failure drills** (`FailureDrillTest`, 5 tests): dispatcher failure injection (email gateway down → retry → dead letter after 5 attempts); job worker failure injection (action handler down → retry → dead letter); duplicate submission idempotency (same request submitted twice → single outbox entry); concurrent duplicate dispatch (two dispatchers claim the same batch → no double-delivery).
- **Redaction** (`RedactionTest`, 5 tests): verify no passwords, tokens, or email addresses leak into API responses (user list, request detail, audit trail, notifications) or structured log output. Password field excluded from all DTOs; JWT token not echoed in responses; correlation ID present but no credential material.
- **Key bugs fixed during test stabilization**:
  - Hibernate 7 JSON dirty-check: `Long` in `Decision.evidence` map round-trips as `Integer` → spurious dirty flag → `UPDATE` blocked by V9 trigger. Fix: store `delegatedTo` as `String.valueOf()`.
  - `OutboxMessageRepository.findByKeyPrefix` used `LIKE :prefix` (exact match) instead of `LIKE :prefix || '%'` (prefix match) — SLA audit events invisible.
  - `JdbcTemplate` cannot bind `java.time.Instant` — must use `java.sql.Timestamp.from(Instant)`.
  - `SimulatedActionHandler` failure injection leaked between test classes (singleton `failuresRemaining` not cleared) — ObservabilityTest job counters at 0. Fix: `clearSimulatedFailure()` + drain pending jobs in `@BeforeEach`.
  - Micrometer `metrics.find(name).counter()` returns only the first tag variant — with multiple `action` tags (PROVISIONING, PURCHASE_TICKET, ACCOUNTING_EXPORT), the wrong counter was returned. Fix: sum across all tag variants via `getMeters()` iteration.

## 11. Local Dev Setup

```bash
# 1. PostgreSQL
docker run -d --name flowops-pg -e POSTGRES_PASSWORD=flowops -e POSTGRES_DB=flowops -p 5432:5432 postgres:16-alpine

# 2. Mailpit (dev SMTP capture — optional but recommended for email testing)
docker run -d --name flowops-mailpit -p 8025:8025 -p 1025:1025 axllent/mailpit:latest

# 3. App (with SMTP email gateway → Mailpit)
FLOWOPS_EMAIL_GATEWAY=smtp ./mvnw spring-boot:run

# 4. Tests (Testcontainers spins its own PG; email gateway defaults to simulated)
./mvnw test
```

### Email Gateway

| `flowops.email.gateway` | Bean | Use |
|--------------------------|------|-----|
| `simulated` (default) | `SimulatedEmailGateway` | Tests, failure injection, no SMTP needed |
| `smtp` | `SmtpEmailGateway` | Dev (Mailpit), prod (real relay) |

- **Simulated**: logs to console, supports `setSimulatedFailure(true, n)` for retry testing. No network I/O.
- **SMTP**: `JavaMailSenderImpl` → `spring.mail.host:port` (defaults `localhost:1025` = Mailpit). 3s timeouts. Throws `EmailGatewayException` on failure → outbox retry/backoff.

### Mailpit Quick Reference

| Endpoint | Purpose |
|----------|---------|
| `http://localhost:8025` | Web UI — browse, search, delete messages |
| `http://localhost:8025/api/v1/messages` | REST: list messages (JSON) |
| `http://localhost:8025/api/v1/messages/{id}` | REST: single message detail |
| `http://localhost:8025/api/v1/messages?raw=1` | REST: raw MIME source |
| `http://localhost:8025/api/v1/info` | REST: server stats (accepted/rejected counts) |

`application.yml` defaults: `MAIL_HOST=localhost`, `MAIL_PORT=1025`. Override for production relay via env vars.

Secrets via env vars.

## 12. Risks / Open Questions

- **Auth model**: JWT with a simple credential login is fine for training — confirm no external IdP needed (doc says out of scope). ✓ assumed JWT
- **JWT vs API keys**: going with JWT (claims carry role + scopes for easier debugging).
- **Threshold scope** as a first-class scope vs. workflow condition: implement in workflow conditions (JSONB) and expose as evidence; permission-level threshold scope only if a story demands it.
- **K_OF_N** rule: supported from iteration 4 (schema ready), stories only exercise ANY_OF.
- **User↔department link**: the PDF never states users belong to a department — it's implied by §7.2 department scope and §4 department managers. **Decision (implemented, iteration 1):** `users.department_id` (nullable) + `departments` table; departmentless users default-deny on DEPARTMENT scopes (§7).
- **Department roster**: PDF §5.1 names Procurement and Compliance as owning departments. **Decision:** seed `PROCUREMENT` + `COMPLIANCE` in iteration 3 alongside the request types (seed currently has FINANCE/IT/HR/OPERATIONS).
- **CLOSED / EXPIRED drivers**: PDF §6.1 lists both without defining them. **Assumption (documented in §5):** `CLOSED` via explicit close action; `EXPIRED` via SLA scanner beyond a configured window.
- **Policy Exception "periodic review"** (PDF §5.1, "may require"): out of scope for this release — no scheduled review jobs.
- **Clarification requests/responses**: scheduled in iteration 7 — lightweight `clarifications` table + endpoints, audited (incl. `system` actor for job/SLA events).
