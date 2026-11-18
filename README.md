# FlowOps

FlowOps is a REST API for managing business requests from draft through approval, execution, and closure. Administrators configure request types and workflows. Requesters submit and track work, approvers act from a scoped queue, and operators handle blocked reviews and uncertain execution. PostgreSQL stores workflow state, execution evidence, audit history, and notification intents.

## Getting started

Use JDK 21 and Docker with Compose. The Maven Wrapper builds the project. Docker also supplies the PostgreSQL instance used by integration tests.

```sh
docker compose up -d
./mvnw clean -pl flowops-web -am -DskipTests package
FLOWOPS_EMAIL_GATEWAY=smtp java -jar flowops-web/target/flowops-web-0.1.0-SNAPSHOT.jar
```

| Local endpoint | Purpose |
|---|---|
| [Swagger UI](http://localhost:8080/swagger-ui.html) | Explore and call the REST API |
| [OpenAPI JSON](http://localhost:8080/v3/api-docs) | API contract |
| [Health](http://localhost:8080/actuator/health) | Application health |
| [Mailpit](http://localhost:8025) | Captured development email when SMTP is enabled |

Sign in through `POST /api/v1/auth/token` using the local seed account `admin` / `admin123`, then supply the returned access token as a bearer token. PostgreSQL defaults to `jdbc:postgresql://localhost:5432/flowops` with local credentials `flowops` / `flowops`. Flyway applies the schema on startup and Hibernate validates it. The repository provides an API and Swagger UI.

```sh
./mvnw clean verify
./mvnw -pl flowops-web -am -Dtest=ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The full build runs domain and application tests, ArchUnit rules, and PostgreSQL integration tests. It packages the executable JAR in `flowops-web/target/`.

## Architecture

FlowOps runs as a modular monolith with one Spring Boot process and one PostgreSQL database. Maven module dependencies point toward the business code.

| Module | Responsibility | Direct project dependencies |
|---|---|---|
| [flowops-domain](flowops-domain/pom.xml) | Business models, lifecycle rules, policies, events, and repository contracts | None |
| [flowops-application](flowops-application/pom.xml) | Use cases, authorization, transactions, query models, and ports | Domain |
| [flowops-infrastructure](flowops-infrastructure/pom.xml) | JPA/JDBC adapters, provider calls, mail, JWT, scheduled workers, and telemetry | Domain, application |
| [flowops-web](flowops-web/pom.xml) | HTTP controllers, DTOs, error mapping, application startup, configuration, Flyway resources, and integration tests | Domain, application, infrastructure |

The domain uses plain Java models. Infrastructure maps them to JPA entities at the persistence boundary. Application services coordinate the domain and outbound ports under Spring transactions. Request controllers dispatch typed commands and queries through the application mediator. Scheduled workers call application operations directly. The web module is the composition root that assembles these pieces using `com.flowops.FlowOpsApplication`.

Business events dispatch synchronously inside the write transaction. Approval, execution planning, lifecycle audit, and outbox intents commit with the state change. Notification delivery runs later. External execution follows a separate durable start/call/finish protocol.

Request list, approval queue, and detail queries use scalar projections. Visibility and queue eligibility are applied before paging and counting. HTTP pages are one-based with default size 20 and a maximum of 100. Detail, list, and queue queries use read-only repeatable-read transactions for consistent progress data. [Architecture rules](flowops-web/src/test/java/com/flowops/ArchitectureTest.java)

## Requests and approval

A request type defines payload validation, the owning department, the default workflow, and execution actions. A workflow defines approval steps, positions, conditions, roles, quorum rules, deadlines, and escalation settings.

| Request state | Behavior |
|---|---|
| `DRAFT` | The requester can edit and submit it |
| `SUBMITTED` | The submission transaction evaluates the workflow and materializes applicable review work |
| `IN_REVIEW` | The request has an active pending or blocked step |
| `APPROVED` | Approval is finalized and the expected execution work is frozen |
| `EXECUTED` | Every action in the frozen plan has completed, or the plan is explicitly empty |
| `REJECTED` | A reviewer rejected the request |
| `CANCELLED` | An authorized cancellation stopped active review and unstarted execution |
| `EXPIRED` | A configured business expiry stopped active review |
| `CLOSED` | An authorized close finalized an executed or rejected request while retaining its outcome |

Review advances through unvisited steps in configured position order. Explicit positions must be positive and unique, and automatic positions fill available slots. An in-progress request can still visit a step missed under the previous ordering. Conditional steps that do not apply are recorded as `SKIPPED`. An all-skipped workflow uses the same finalizer as human approval. `ANY_OF` requires one distinct approval, `K_OF_N` requires the configured quorum, and `ALL_OF` uses the roster captured at activation. Each effective approval counts once per person. Delegation preserves a slot's history and transfers responsibility subject to current eligibility.

Approval checks use the active account, current permission and scope, participant assignment, decisions already made, deadlines, and separation of duties. The requester is excluded from ordinary review decisions. Previously recorded votes remain valid when an approver later loses access. Older steps without captured rosters retain their role/delegation semantics. Current eligible participants may read the request context needed for review. Audit access requires its own grant.

Request writes acquire the parent request lock before mutating the request or its children. The application reloads the latest state after locking. Client decisions should include `expectedVersion` and `stepInstanceId` so a delayed command cannot act on a newer step. A stale version, stale step, or lock timeout returns HTTP 409. Background sweeps use bounded discovery and independent parent-first transactions that skip busy roots and revalidate eligibility or due work under the lock.

### Blocked review and deadlines

A step becomes `BLOCKED` when the remaining eligible participants cannot reach the approval threshold. The request stays `IN_REVIEW`. A block tracks its reason, episode, time, and recovery owner or team. An authorized operator can recheck eligibility, refresh an eligible numeric pool, or record a replacement for an unvoted slot. The original roster size, effective votes, and quorum remain historical evidence. Recovery requires a new valid vote to satisfy an outstanding approval.

The blocked queue and recovery history require `request:blocked:read` in the applicable scope. Recovery and recheck require `request:recover`, the current `stepInstanceId` and `expectedVersion`, and a reason. Recheck evaluates the existing roster. Recover can add eligible participants to numeric pools or replace unvoted slots explicitly. An existing `ALL_OF` roster retains its slots and threshold.

Approval-relevant user and role changes enqueue durable reconciliation hints. An after-commit wakeup processes them promptly, and a periodic sweep handles missed wakeups. Ownership is reassessed against current operator scope, with the configured fallback used when eligible and an unavailable owner shown explicitly when no eligible owner is found.

Activation freezes the review due date and any independent business expiry. The review due date starts recurring escalation. The interval and optional maximum level are frozen with the step. A blocked episode also has its own reminder schedule. Escalations are deduplicated by episode and level, and a delivery attempt with no recipients backs off without consuming a level. A configured business expiry stops review when reached, including a blocked step.

### Execution and recovery

Approval freezes an immutable plan with the ordered action set, original payload, per-action idempotency keys, and approval evidence. Job bindings and materialization receipts tie work to the original plan and job identities. An explicitly empty plan completes immediately. Every nonempty plan requires exact successful job coverage. Missing, invalid, or extra work prevents completion.

1. A worker discovers due jobs, then locks the request before the job. It commits the start latch, fenced attempt lease, immutable start evidence, and selected provider contract before making the external call.
2. The provider call runs outside row-lock transactions, within bounded execution capacity and a timeout. A separate heartbeat scheduler can renew the live claim up to the attempt's original deadline.
3. A separate transaction applies the result only to the matching live claim. The shared completion check commits the `EXECUTED` transition and completion notification once.

Cancellation uses the same parent lock. If cancellation wins before durable start, it stops runnable jobs in the same transaction. A durable start or unresolved execution history blocks ordinary cancellation.

Timeouts, expired claims, and ambiguous failures can leave a job on hold. Provider reconciliation records a command in one transaction, performs a read-only lookup outside the transaction, and applies the observation in a fenced transaction. Provider identity, attempt identity, command order, lease, and current operator authority constrain the result. Recovery calls take a command UUID, expected request version, and reason. An identical authorized replay returns the existing receipt.

| Provider observation | Result |
|---|---|
| `SUCCEEDED` with authoritative evidence | Complete the held job and recheck the plan |
| `SAFE_TO_RETRY` with authoritative evidence | Permit explicit retry when the provider has fenced any prior attempt |
| `IN_PROGRESS` | Keep the job held pending further evidence |
| `UNKNOWN` | Keep the job held. A retry requires the applicable stable-key idempotency guarantee |
| Lookup failure | Record provider error and require a fresh accepted reconciliation |

An authorized retry preserves the job ID, frozen input, idempotency key, first-start evidence, and attempt counters. Periodic plan reconciliation restores only provably unstarted missing materializations using the original recorded identities. Ambiguous and started work is surfaced for review.

Historical approvals with no frozen plan require an explicit disposition. An operator with scoped `execution:plan:dispose` can submit `ADOPT` with a complete source-backed manifest or `KEEP_QUARANTINED` with supporting evidence. Both require a command ID, expected version, reason, and evidence reference. Adoption pins provider identities and holds unresolved work for normal provider reconciliation.

Request responses include status, retained outcome, milestone timestamps, and live execution progress. An unknown historical milestone is null. A known empty plan has zero expected jobs. Progress reflects current manifest, binding, job, and blocked-review evidence. Successful recovery can clear its attention flag while the audit history is retained.

### Audit and delivery

Lifecycle records are append-only and capture business transitions, votes, assignments, recovery, and execution evidence in the same transaction as their state changes. Older requests can include reconstructed entries marked with their provenance. Operator attempt history omits claim tokens and frozen input. Request correlation IDs link audit facts, structured logs, and tracing.

Email and feed intents use a transactional outbox with deduplicated keys, retries, and dead letters. Email uses a simulated gateway by default, or SMTP when configured. SMTP delivery is at least once. A crash after the relay accepts a message and before the delivery transaction commits can produce a duplicate. Micrometer metrics and OpenTelemetry instrumentation cover request lifecycle, job execution, outbox processing, and SLA work.

## Authentication and authorization

`POST /api/v1/auth/token` accepts a username and password, returning a JWT access token and opaque refresh token with their expirations. `POST /api/v1/auth/refresh` rotates the refresh token and returns a new pair. Clients store the latest refresh token and send access tokens as `Authorization: Bearer <token>`. Credential responses use `Cache-Control: no-store`.

Refresh tokens are stored by hash. Session-row locking serializes rotation and logout. Replaying a consumed token revokes its session. Credential or account changes prevent subsequent refresh. The original session deadline caps both token lifetimes, and already-issued JWTs remain valid until expiry. `POST /api/v1/auth/logout` revokes the current session and `POST /api/v1/auth/logout-all` revokes the caller's sessions. The password-reset flow issues expiring, single-use tokens.

Permissions are granted through roles and can be global or constrained by self, department, request type, step, or payload amount. Application authorization uses current persisted account and grant data for sensitive operations. Recovery and audit have distinct permissions, and execution rollout inspection requires a global `execution:rollout:read` grant.

## REST API

Paths below are relative to `/api/v1`. Swagger lists request bodies, parameters, and complete response schemas. Error responses use Problem Details and include the request correlation ID.

| Capability | Routes |
|---|---|
| Authentication | `POST /auth/{token,refresh,logout,logout-all,forgot-password,reset-password}` |
| Administration | `/users`, `/roles`, `/permissions`, `/departments`, `/request-types`, `/workflows` |
| Request lifecycle | `GET/POST /requests`, `GET/PUT /requests/{id}`, `POST /requests/{id}/{submit,decisions,cancel,close}` |
| Review | `GET /requests/queue`, `GET /requests/{id}/{participants,eligible-delegatees}`, `GET/POST /requests/{id}/clarifications`, `PUT /requests/{id}/clarifications/{cid}` |
| Blocked work | `GET /requests/blocked`, `POST /requests/{id}/{recover,recheck}`, `GET /requests/{id}/recoveries` |
| Execution | `GET /requests/{id}/execution-plan`, `GET /requests/{id}/execution-recovery`, `POST /requests/{id}/execution-recovery/{jobId}/{reconcile,retry}` |
| Execution evidence | `GET /requests/{id}/execution-recovery/{jobId}/{attempts,attempt-events}`, `GET/POST /requests/{id}/legacy-plan-dispositions` |
| Audit and notifications | `GET /requests/{id}/audit`, `GET /notifications`, `GET /notifications/unread-count`, `PATCH /notifications/{id}/read`, `PATCH /notifications/read-all` |
| Rollout inspection | `GET /execution/rollout?phase=PRE_ENABLE` or `phase=POST_ENABLE` |

## Configuration

The complete configuration and development defaults are in [application.yml](flowops-web/src/main/resources/application.yml).

| Area | Key environment variables |
|---|---|
| Database | `DB_URL`, `DB_USER`, `DB_PASSWORD` |
| Authentication | `FLOWOPS_JWT_SECRET`, `FLOWOPS_JWT_EXPIRY` (900 seconds), `FLOWOPS_REFRESH_TOKEN_LIFETIME` (7d), `FLOWOPS_MAXIMUM_SESSION_LIFETIME` (30d), `FLOWOPS_ADMIN_PASSWORD` |
| Email and links | `FLOWOPS_EMAIL_GATEWAY`, `FLOWOPS_EMAIL_FROM`, `MAIL_HOST`, `MAIL_PORT`, `MAIL_USER`, `MAIL_PASS`, `FLOWOPS_BASE_URL`, `FLOWOPS_FE_BASE_URL` |
| Review | `FLOWOPS_REQUEST_LOCK_TIMEOUT_MILLIS` (5000), `FLOWOPS_SLA_BATCH_SIZE`, `FLOWOPS_RECOVERY_BATCH_SIZE`, `FLOWOPS_RECOVERY_TEAM_ROLE`, `FLOWOPS_RECOVERY_FALLBACK_USERNAME` |
| Execution | `FLOWOPS_EXECUTION_WORKER_ENABLED`, `FLOWOPS_COMPLETION_RECONCILIATION_ENABLED`, `FLOWOPS_EXECUTION_LEASE_SECONDS`, `FLOWOPS_EXECUTION_HEARTBEAT_SECONDS`, `FLOWOPS_EXECUTION_MAX_ATTEMPT_SECONDS` |
| Provider capacity | `FLOWOPS_PROVIDER_EXECUTION_SLOTS`, `FLOWOPS_PROVIDER_LOOKUP_SLOTS`, `FLOWOPS_ROLLOUT_REQUIRE_LIVE_PROVIDER` |

Use a deployment-specific JWT secret, database credentials, seed-account policy, SMTP relay, and application URLs outside development. The configured external action adapter is a simulator. Enable the live-provider gate for production execution and verify each real adapter's provider identity, timeouts, idempotency, and recovery guarantees.

## Fresh database initialization and release checks

The active Flyway history starts fresh with three migrations:

| Migration | Purpose |
|---|---|
| `V1__initial_schema.sql` | Final tables, columns, sequences, constraints, indexes and views |
| `V2__integrity_guards.sql` | Final workflow, audit, execution and recovery functions/triggers |
| `V3__seed_reference_data.sql` | Final departments, RBAC grants, local accounts, request types and workflows |

These migrations preserve the current application behavior without replaying historical
schema alterations or data backfills. Runtime compatibility tables for imported/legacy
evidence exist but start empty. No requests, decisions, audit history, execution jobs or
legacy dispositions are seeded. Initialization sets the provider-contract enforcement
marker to the actual initialization time.

**This is a fresh-install baseline, not an upgrade for a database carrying the old
V1–V31 Flyway history.** Keep existing data backed up and select a new empty database.
Do not repair checksums, disable Flyway validation or remove only the history table to
force this schema onto existing tables. No database is deleted by these scripts.

For example, with the local Compose PostgreSQL service already running, create a
separate database and point the new build at it:

```sh
docker compose exec postgres createdb -U flowops flowops_fresh
./mvnw clean -pl flowops-web -am -DskipTests package
DB_URL=jdbc:postgresql://localhost:5432/flowops_fresh \
  java -jar flowops-web/target/flowops-web-0.1.0-SNAPSHOT.jar
```

Use `clean` when switching from the old migration resources so stale files do not remain
in target/classes. After this baseline has been installed, preserve its checksums and
add future changes as V4 and higher. The previous migration chain, historical preflight
scripts and upgrade-only tests have been removed. Fresh-bootstrap tests verify
initialization, catalog data, integrity guards and empty application history.

The [current read-only database checks](flowops-web/src/main/resources/db/checks) target
this V3 bootstrap.

1. Record the tested build and fresh database identity. Verify backup restoration, migration duration, lock impact, and client compatibility. Confirm scoped operator coverage, fallback ownership, alert routing, real-provider behavior, and scheduler latency under load.
2. Stop old application processes, schedulers, and ad hoc writers. Gate user and operator writes and account for in-flight provider calls before migration. The execution flags pause execution and completion polling only. Review, identity, SLA, and outbox work can still run in a normal application process. Use a separate migration and inspection phase when the maintenance window requires all writes or deliveries to stop.
3. Validate the upgraded schema and run the two current acceptance queries in a read-only snapshot.

```sql
\set ON_ERROR_STOP on
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
\i flowops-web/src/main/resources/db/checks/m1_m4_release_acceptance.sql
\i flowops-web/src/main/resources/db/checks/m3_rollout_acceptance.sql
ROLLBACK;
```

4. Inspect the full `PRE_ENABLE` report on every process with execution and completion polling paused. Resolve blockers and assign owners to attention items. Verify stale commands, blocked recovery, escalation versus expiry, both outcomes of an execution/cancellation race, provider timeouts and duplicate-effect protection, legacy disposition, and notification delivery.
5. Enable both execution pollers on the reviewed instances, check `POST_ENABLE`, and observe a canary through representative review, execution, and recovery windows. Review every inconclusive provider result before authorizing any replay.

```sh
python3 scripts/check_m3_rollout.py --base-url http://localhost:8080 --phase PRE_ENABLE
python3 scripts/check_m3_rollout.py --base-url http://localhost:8080 --phase POST_ENABLE
```

Supply `FLOWOPS_OPERATOR_TOKEN` through the environment. The probe exits 0 for passing machine checks, 2 for blockers, and 1 when it cannot obtain a valid report. Release acceptance also requires review of the complete reports, database checks, all-instance maintenance controls, and provider evidence. If rollout fails, preserve records, pause affected work, and reconcile provider effects before resuming. A database restore cannot undo an external effect.

[Original project requirements](FlowOps_Project_Overview_and_Requirements.pdf)
