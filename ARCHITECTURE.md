# FlowOps architecture

FlowOps is a modular monolith with four Maven modules. Dependencies point toward
business code; deployment remains one Spring Boot server and one database.

| Module | Responsibility | Project dependencies |
|--------|----------------|----------------------|
| `flowops-domain` | Business models, invariants, policies, events and aggregate ports | None |
| `flowops-application` | Commands, queries, orchestration, read models and application ports | domain |
| `flowops-infrastructure` | Persistence, mail, external actions, workers, security and observability | domain, application |
| `flowops-server` | REST API, Spring composition root, configuration, migrations and integration tests | domain, application, infrastructure |

The former `flowops-infra` and `flowops-app` modules are named
`flowops-infrastructure` and `flowops-server`. Their artifact ids and reactor paths
follow those names. The bootable jar is produced in `flowops-server/target/`.

## Package organization

```text
flowops-domain/src/main/java/com/flowops/domain/
  request/          Request, StepInstance, Decision, Clarification, statuses
    event/          RequestSubmitted, RequestApproved, RequestRejected, DecisionDelegated
    policy/         WorkflowDecisionPolicy
    port/           Aggregate repository contracts
    query/          RequestFilter
  workflow/         Workflow, WorkflowStep, RequestType, definitions/specifications
    policy/         ConditionEvaluator, PayloadValidator
    port/           Workflow and request-type repository contracts
    query/          Workflow and request-type filters
  identity/         User, Role, Permission, CallerIdentity, password reset tokens
    policy/         AuthorizationEvaluator, TargetContext, permission vocabulary
    port/           Identity repository contracts
    query/          Identity filters
  organization/     Department, its repository contract and filter
  execution/        ExecutionJob, job vocabulary and execution ports
  notification/     User-facing Notification and its repository contract
  audit/            AuditEvent
  event/            Shared DomainEvent marker
  exception/        Shared domain exceptions
  paging/           Framework-free PageQuery and PageResult
  CorrelationId     Shared correlation vocabulary

flowops-application/src/main/java/com/flowops/application/
  request/
    command/        Lifecycle command messages and mediator handlers
    query/          Request/queue/delegatee query messages and handlers
    lifecycle/      CreateDraft, UpdateDraft, SubmitRequest, DecideStep, etc.
    workflow/       StepMaterializer
    sla/            ScanSla
    model/          RequestListItem, RequestDetailView, command RequestDetail
    port/           Read-side ports, lifecycle observability and SLA metrics
    RequestDetailReader
  workflow/         Workflow and request-type management; workflow/model/WorkflowView
  identity/         User management and password reset orchestration
    session/        Login, refresh rotation, revocation, settings and expired-session cleanup
    port/           Access-token issuer and authentication-session storage contracts
  organization/     Department, role and permission management
  notification/     Templates, notification subscribers and feed orchestration
    model/          EmailLogEntry delivery audit record
    port/           EmailGateway and EmailLogEntryRepository
    outbox/         OutboxService, DeliverOutbox, message/payload/status/type vocabulary
      port/         Message repository, payload codec and dispatch observability
  execution/        Job scheduling, approval subscriber and ProcessExecutionJobs
    port/           Execution observability
  audit/            Audit read service
  mediator/         Typed synchronous command/query dispatch
  event/            Synchronous multi-subscriber business-event dispatch
  config/           FlowOpsUrls configuration data

flowops-infrastructure/src/main/java/com/flowops/
  persistence/      Aggregate adapters, query adapters and Spring Data repositories
    entity/         JPA entities
    mapping/        Aggregate/entity mappers
  mail/             Simulated and SMTP email gateways
  action/           External execution adapter
  worker/           Job/outbox/SLA scheduling shells and hourly authentication-session cleanup
  security/         JWT handling, UserPrincipal and security configuration
  observability/    Micrometer/OpenTelemetry adapters and configuration

flowops-server/src/main/java/com/flowops/
  api/              Controllers
    dto/            HTTP request/response records
    web/            Error handling, correlation filter and pagination conversion
  config/           Composition/configuration beans
  FlowOpsApplication
```

Infrastructure packages use `com.flowops.persistence`, `com.flowops.mail`, etc.,
without an additional `infra` segment. Domain and application retain their layer
namespaces so their roles remain identifiable on the combined runtime classpath.
Within those namespaces, models, ports and operations are grouped by capability.
The generic application `usecase`, `model`, `org` and `port` packages have been removed.

`FlowOpsApplication` stays at `com.flowops`, allowing component, entity and repository
scanning to discover all four modules. Explicit JPA entity names, table/column
mappings, Flyway migrations and API routes are unaffected by package relocation.

## Domain and persistence

The domain depends only on the JDK and slf4j. Spring, Hibernate and JPA stay outside
it. JPA entities live in `com.flowops.persistence.entity`; mappers convert aggregates
at adapter boundaries. Domain capability ports expose no persistence-framework types.

`Request` has a private constructor, guarded lifecycle methods and defensively copied
nested JSON payload maps/lists. `Request.restore(Request.Snapshot)` restores database
state without replaying transitions; `recordPersistence` acknowledges generated ids
and lock versions. An architecture rule restricts those calls to persistence adapters.
Other aggregates retain their existing restoration setters.

Request saves preserve generated identity and optimistic-lock versions. The package
reorganization retains the existing persistence behavior, including `saveAndFlush`
and the request-version conflict guard. It introduces no schema migration.

`WorkflowDecisionPolicy` performs no I/O. It owns supported-action/review validation,
rejection, delegation without advancement and completion against the materialized
step's approval threshold. Rejection keeps the pending step for audit compatibility.
Application lifecycle operations retain authorization, decision evidence, repository
writes and workflow materialization. Mediator handlers retain their outer transactions
through command-response assembly; transactional lifecycle operations join them.

## Commands, queries and events

`Mediator.send(Message<R>)` dispatches to exactly one `MessageHandler<M,R>` on the
caller's thread. Handlers register explicit message classes, so Spring proxies work
without generic-type reflection. Duplicate registrations fail at startup; unknown
messages fail at dispatch. Handler exceptions propagate unchanged.

Request controllers depend on the mediator. Command handlers own write transactions;
query handlers own read-only transactions. Authorization remains explicit in the
application operations/handlers. Submit, decide and close preserve lifecycle
observability around their operation. Worker shells call capability-local application
operations directly rather than passing through HTTP response handlers.

Business events use a separate `DomainEventPublisher` and `DomainEventHandler<E>`
contract. The publisher requires an existing transaction (`MANDATORY`) and directly
invokes the ordered Spring handler proxies synchronously. There is no async or
after-commit dispatch. Missing subscribers fail explicitly; subscriber failures stop
dispatch and mark the enclosing transaction for rollback.

| Event | Publisher | Subscribers in order |
|-------|-----------|----------------------|
| RequestApproved | DecideStep | NotifyRequestApproved (100), EnqueueApprovedRequestJobs (200) |
| RequestSubmitted | SubmitRequest | NotifyRequestSubmitted (100), NotifySubmittedRequestApprovers (200) |
| RequestRejected | DecideStep | NotifyRequestRejected (100) |
| DecisionDelegated | DecideStep | NotifyDecisionDelegated (100) |

Events contain ids and immutable decision details. Subscribers load the saved request
inside the same transaction. Approval/submission add two repository lookups; rejection
and delegation add one. State changes, decisions, jobs and outbox entries commit or
roll back together. The durable outbox workers deliver committed messages later.
Existing idempotency keys, payloads, recipients and correlation arguments are preserved.

Submission with all steps skipped still emits only RequestSubmitted, with no additional
approval notifications/jobs. Next-step approver notification after an approval still
uses ApproverNotifier from DecideStep. These semantics are unchanged by relocation.

## Query projections

Application request ports define RequestListQueries, ApprovalQueueQueries and
RequestDetailQueries. Their infrastructure implementations select scalar read models
rather than entities or aggregate graphs. DTO conversion remains at the HTTP edge.
Authorization's user/permission lookups are separate from the following query counts.

List queries pass the authenticated caller id and an independent list-all flag. The
existing request specifications apply visibility, type/status and `mine` filters before
paging and counting. `mine=true` works for privileged callers because their id is
retained. Each page uses two statements: scalar projection and count. Optional type
owning departments use a left join.

Approval queues apply request IN_REVIEW state, requester exclusion and correlated
EXISTS predicates for pending steps. Eligibility requires a matching role or a DELEGATE
decision addressed to the caller on that same pending instance. EXISTS prevents duplicate
rows/counts from repeated matches. Empty roles preserve the existing empty queue behavior.
Eligibility is part of the page/count statements; there is no intermediate id list.

GET detail loads a scalar header, authorizes the same owner/scoped-read target (including
payload amount), then loads history. Steps and decisions retain id ordering; clarifications
retain creation-time descending ordering and jobs ascending ordering. Decisions are loaded
in one batch and grouped by step instance, preserving historical actor names and evidence.
GET detail uses five statements regardless of step count, or four without steps. Its
RequestDetailView contains no aggregates and remains usable after the session closes.

Command responses retain RequestDetail and RequestDetailReader inside their write
transactions. DecisionRepository.findByRequestIdOrderById replaces per-step reads there,
while preserving grouping and empty histories. All HTTP response shapes remain unchanged.

## Pagination

Every paginated HTTP endpoint uses one-based page numbers. Omitted page defaults to 1;
page=1 returns the first page, page=2 the second. Values below 1 return HTTP 400.
Default size remains 20, with normalization to 1–100.

`api.web.Pagination` converts to zero-based PageQuery values and canonical whitelisted
sort fields. PageResponse converts internal page numbers back to one-based values;
first/last flags and total-page semantics include empty/out-of-range pages. Notification
feeds, including the manually paged unread path, use the same convention. Persistence
and application paging remain zero-based.

## Architecture enforcement and verification

Fifteen ArchUnit rules enforce inward dependencies, framework/persistence-free domain
code, persistence-only request restoration, telemetry placement and restricted worker
entry points. Infrastructure rules enumerate all flattened adapter namespaces. The HTTP
security edge projects UserPrincipal; token issuance is behind an application-owned
AccessTokenIssuer. Other API-to-infrastructure dependencies are prohibited. Obsolete package roots cannot be reintroduced.

Tests include domain invariants and decision policy, mediator/event dispatch and Spring
proxy behavior, API authorization/pagination, scalar projection equivalence and SQL counts,
concurrent decisions, transactional rollback, event replay idempotency, outbox delivery and
execution recovery. Non-worker integration contexts mock polling shells so cached Spring
contexts cannot race manual worker failure drills against the shared PostgreSQL container.

Run the full reactor with `./mvnw clean test`. Integration tests use Testcontainers PostgreSQL.
The root Maven reactor and module dependencies use the renamed artifacts throughout.

## Email and outbox ownership

Email delivery and the transactional outbox are application delivery mechanisms.
`EmailGateway` and email audit contracts belong to `application.notification.port`;
`EmailLogEntry` belongs to `application.notification.model`. All outbox records,
payloads, status/retry behavior, notification codes, repository/codec/observability
ports and dispatch services belong to `application.notification.outbox` and its
`port` package. Their persistence, SMTP, JSON and telemetry adapters remain in
infrastructure. A dedicated architecture rule prevents these types from returning
to the domain.

The domain retains user-facing Notification/read state and request lifecycle facts
such as RequestApproved. Domain code has no email-gateway or outbox references.
Execution scheduling owns its job-key format independently of the outbox; its stored
`req:<requestId>:<action>` keys remain unchanged. Outbox keys, SQL mappings, JSON
payloads, retry/dead-letter behavior and transactional atomicity are unchanged.

## Authentication lifecycle

See [AUTHENTICATION.md](AUTHENTICATION.md) for endpoints, expiry configuration and rollout.
Application authentication services own refresh-token rotation and session revocation;
JWT and JDBC adapters implement their ports. Refresh credentials are opaque and hashed,
rotation is serialized with a session-row lock, and replay revokes the token family.
Access JWT authentication is stateless: signature, issuer/claims and expiration are
validated without a session lookup. Logout, replay and credential changes prevent
refresh; already-issued access tokens remain valid until their short expiration.
Credential fingerprints and account status are checked when refreshing. Absolute
session expiry bounds both access and refresh lifetimes. Flyway V15 adds the storage; hourly cleanup removes
expired families. The domain contains no JWT or refresh-session mechanism.
