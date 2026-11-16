# FlowOps — Architecture

Clean Architecture with DDD rich domain model.
Layer boundaries are enforced **twice**: structurally (4 Maven modules — an
illegal dependency is a compile error) and by `ArchitectureTest`
(13 ArchUnit rules for the seams Maven can't see).

## Modules

```
flowops (parent pom)
├── flowops-domain        pure domain core — JDK + slf4j ONLY
│                         (aggregates, ports, policy; zero framework deps)
├── flowops-application   use cases, per-bounded-context services, ports,
│                         read models — depends on: domain
├── flowops-infra         JPA adapters, workers, mail, actions, observability
│                         adapters, security (JWT filter chain) —
│                         depends on: domain, application
└── flowops-app           bootable: REST API (controllers + web edge),
                          config, FlowOpsApplication, migrations, tests —
                          depends on: everything; spring-boot repackage
```

| Module | Key dependencies (beyond the modules it uses) |
|--------|-----------------------------------------------|
| `flowops-domain` | slf4j-api |
| `flowops-application` | spring-context, spring-tx, spring-security-crypto |
| `flowops-infra` | spring-boot-starter-data-jpa/-mail/-security/-web, micrometer, OpenTelemetry, jjwt, Jackson 3 |
| `flowops-app` | spring-boot-starter-web/-validation/-actuator, flyway, postgresql, springdoc, testcontainers, archunit (test) |

Shared vocabulary classes live in the domain so every module can reference
them: `domain.exception.ApiErrors` (RFC 7807 `urn:flowops:error:*` namespace)
and `domain.CorrelationId` (header + MDC key names).

## Layer Dependency Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│  api/  [flowops-app]                                                            │
│  ├── *Controller.java    REST endpoints, DTO mapping, auth projection           │
│  ├── dto/                Request/Response DTOs (20 records, mapping-only)       │
│  └── web/                HTTP edge: RFC 7807 exception handlers, ProblemDetails │
│                          advice, CorrelationIdFilter (error URIs come from     │
│                          domain.exception.ApiErrors)                           │
│                                                                        │       │
│  depends on:  domain (DTO mapping) + application (service calls)          │       │
└──────────────────────────────────────────────────────────────────────────────────┘
         │                                          │
         ▼                                          ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│  application/  [flowops-application]                                             │
│  ├── request/         command/, query/, ClarificationService                      │
│  ├── identity/        UserService, PasswordResetService                         │
│  ├── workflow/        WorkflowService, RequestTypeService                       │
│  ├── notification/    OutboxService, NotificationService, EmailComposer         │
│  ├── execution/       ExecutionService                                          │
│  ├── audit/           AuditService                                              │
│  ├── org/             DepartmentService, RoleService, PermissionService         │
│  ├── usecase/         One class per workflow action                             │
│  │    CreateDraft, UpdateDraft, CancelRequest, SubmitRequest,                  │
│  │    DecideStep, CloseRequest, StepMaterializer, EligibleDelegatees,          │
│  │    ScanSla (SLA breach/expiry), ProcessExecutionJobs (job retry),           │
│  │    DeliverOutbox (outbox channel routing + retry)                           │
│  ├── port/            Driven-side ports (RequestLifecycleObservability,        │
│  │                    SlaMetrics, ExecutionJobObservability, OutboxObservability│
│  │                    — implemented by the observability module) +              │
│  │                    FlowOpsUrls (config record, replaces @Value)              │
│  └── model/           Read models (RequestDetail, WorkflowView)                │
│                                                                        │       │
│  depends on:  domain only (entities, ports, services, exceptions)      │       │
└──────────────────────────────────────────────────────────────────────────────────┘
         │
         ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│  domain/  [flowops-domain]   THE CORE — pure Java (JDK + slf4j only)            │
│  ├── (root)         Aggregates: Request, Workflow, WorkflowStep, StepInstance,  │
│  │                  Decision, Clarification, User, Role, Permission,            │
│  │                  Department, RequestType, ExecutionJob, OutboxMessage,       │
│  │                  Notification, EmailLogEntry, OutboxPayload (value object)   │
│  ├── exception/     BusinessRuleViolation, NotFound, Forbidden, …              │
│  ├── identity/      CallerIdentity (framework-free identity record)             │
│  ├── paging/        PageQuery, PageResult (framework-free paging twins)         │
│  ├── policy/        AuthorizationEvaluator, PermissionCodes, TargetContext,     │
│  │                  AuthorizationDecision                                       │
│  ├── port/          Pure-Java repository ports (12 interfaces)                  │
│  ├── query/         Filter objects (RequestFilter, WorkflowFilter, …)          │
│  └── service/       Domain services (PayloadValidator, ConditionEvaluator)      │
│                                                                        │       │
│  depends on:  JDK + slf4j ONLY                                   │       │
│  forbidden:   Spring, JPA/Hibernate, security, infra, application, api │       │
└──────────────────────────────────────────────────────────────────────────────────┘
         ▲
         │  implements ports
┌──────────────────────────────────────────────────────────────────────────────────┐
│  infra/  [flowops-infra]                                                        │
│  ├── persistence/     entity/ (JPA entities, one per aggregate) +               │
│  │                   mapping/ (aggregate ↔ entity mappers) +                    │
│  │                   *JpaRepository (Spring Data) + @Repository adapters        │
│  │                   (SpringWorkflowRepository, SpringStepInstanceRepository,  │
│  │                   …) + Paging translator + Specifications                    │
│  │                   + JacksonOutboxPayloadCodec (implements                    │
│  │                   domain.port.OutboxPayloadCodec — infra owns JSON shape)    │
│  ├── mail/            SMTP sender (implements domain.mail port)                │
│  ├── action/          External action adapters (execution jobs)                │
│  └── worker/          Thin @Scheduled shells (SlaScanner → ScanSla,            │
│  │                   JobWorker → ProcessExecutionJobs,                         │
│  │                   OutboxDispatcher → DeliverOutbox)                         │
│                                                                        │       │
│  depends on:  domain (entities + ports) + Spring + infra libraries    │       │
└──────────────────────────────────────────────────────────────────────────────────┘

  security/  [flowops-infra]    JWT filter, UserPrincipal (projects to CallerIdentity)
                   depends on: domain.identity + domain.exception.ApiErrors (error URIs)
                   forbidden:   application

  observability/  [flowops-infra]   FlowOpsSpans (OTel helper), TracingConfig,
                   SpanNames/MetricNames registries,
                   RequestLifecycleObservabilityAdapter, SlaMetricsAdapter,
                   ExecutionJobObservabilityAdapter, OutboxObservabilityAdapter
                   (implement application ports)
                   depends on:  application.port + domain (read-only) + OTel/Micrometer

  config/  [flowops-app]        OpenApiConfig (API docs), DomainServiceConfig (domain-service
                   beans), FlowOpsUrlsConfig (binds flowops.* URLs into the
                   FlowOpsUrls record)

  FlowOpsApplication  [flowops-app]   @SpringBootApplication + @EnableScheduling
```

## Dependency Rules (ArchUnit — 11 rules in `ArchitectureTest`)

| # | Rule | Rationale |
|---|------|-----------|
| 1 | `domain` must not depend on `api` | Domain is framework-agnostic core |
| 2 | `domain` must not depend on `application` | Use cases call domain, not vice versa |
| 3 | `domain` must not depend on `security`, `infra`, `observability` | No cross-cutting concerns in core |
| 4 | `api` must not depend on `infra` | Controllers go through application services only |
| 5 | `domain` must be Spring-free | No `org.springframework.*` in domain |
| 6 | `application` must not depend on `api` | Services don't know about HTTP |
| 7 | `application` + `domain` must not depend on `security` | Identity is projected at the API edge |
| 8 | `application` must not depend on `infra` | Application code is framework-agnostic |
| 9 | `application` must not use Spring `dao`/`orm`/`data`/`web`/`scheduling` | Adapters own framework plumbing; services see ports only |
| 10 | OpenTelemetry/Micrometer only in `infra` + `observability` | Telemetry never leaks into domain/app/api/security |
| 11 | `infra.worker` may only depend on `application.usecase` + `application.port` (never other application classes) | Workers are thin shells over use cases |

## Security / Identity Flow

```
JWT Token
  │
  ▼
security/JwtAuthenticationFilter  →  Authentication in SecurityContext
  │
  ▼
api/*Controller  @AuthenticationPrincipal UserPrincipal
  │
  ▼  caller.toIdentity()   ← projection at the API edge
  │
domain.identity.CallerIdentity(id, username, roles)
  │
  ▼
application/usecase/*  +  domain.policy.AuthorizationEvaluator
```

`UserPrincipal` is a Spring Security type that only ever appears in `security/` and `api/`.
Everything inward of the API edge sees only `CallerIdentity`.

## Web Edge & Error Contract

- `domain/exception/ApiErrors` (shared domain vocabulary) owns the RFC 7807
  `type` namespace (`urn:flowops:error:*`); the MVC exception handlers, the
  `ProblemDetails` advice, and the security filter chain (401/403) all derive
  their `type` URIs from it.
- Authentication failures surface as the domain exception
  `domain.exception.InvalidCredentialsException`; `api/web/ApiExceptionHandler`
  maps it to `401` — the application layer never names an HTTP status.
- Validation failures throw `domain.exception.RequestValidationException`
  (field → violation map) and are rendered as RFC 7807 `400` with `violations`.

## Paging

Domain ports use framework-free `PageQuery` / `PageResult` (in `domain.paging`).
The `infra.persistence.Paging` translator converts to/from Spring Data `Pageable` / `Page`.
Controllers build `PageQuery` directly via `UserController.buildPageRequest(...)`.

## Persistence Pattern

```
domain/port/RequestRepository.java       ← pure Java interface (port)
  ▲
  │ implements
  │
infra/persistence/SpringRequestRepository.java   ← @Repository adapter
  │                                               (maps domain ↔ entity)
  │ delegates to
  ▼
infra/persistence/RequestJpaRepository.java      ← Spring Data JPA repository
                                                    (operates on RequestEntity)
```

12 ports, 12 Spring Data repositories, 12 `@Repository` adapters,
16 JPA entities (`infra.persistence.entity`) and 16 mappers
(`infra.persistence.mapping`).

Idempotent writes go through port methods like `saveIfAbsent(…)` — the
duplicate-key handling (Spring `DataIntegrityViolationException`) is caught
inside the adapter, so application code never sees Spring exception types.

## Domain Services

| Service | Location | Spring? | Purpose |
|---------|----------|---------|---------|
| `PayloadValidator` | `domain.service` | No | Validates request payload against type schema |
| `ConditionEvaluator` | `domain.service` | No | Evaluates workflow step conditions (SpEL) |
| `AuthorizationEvaluator` | `domain.policy` | No | Scoped permissions, SoD, evidence trail |

Wired as beans via `config.DomainServiceConfig` (`@Configuration`).

## Domain Policy

Business policy lives on the aggregates, not in workers or services:

- **SLA windows** — `StepInstance.isSlaBreached(now)` / `isExpired(now)`
  (breach at `createdAt + slaMinutes`, expiry at `× EXPIRY_MULTIPLIER (2)`),
  plus `escalationRole()` (escalation role with required-role fallback).
- **Retry backoff** — `ExecutionJob.retryBackoff()` and
  `OutboxMessage.retryBackoff()` (base 5s × 2^retryCount), used by the
  `ProcessExecutionJobs` use case and the `OutboxDispatcher`.

Workers keep only infra concerns: tick cadence, batch claiming through
ports, and span boundaries.

## Observability

- Lifecycle spans + counters (PLAN §8) are emitted by
  `observability.RequestLifecycleObservabilityAdapter`, which implements the
  framework-free `application.port.RequestLifecycleObservability` port that
  request command handler calls — no `io.opentelemetry` / `io.micrometer` types in
  the application layer.
- `observability.FlowOpsSpans` wraps span start/status/end; `SpanNames` and
  `MetricNames` are the shared name registries.
- Worker observability also goes through ports: `SlaMetrics`
  (breach/expiry counters), `ExecutionJobObservability` (one
  `job.execute` span per job, completed/failed/dead-letter counters,
  pending gauge) and `OutboxObservability` (dispatch timer, one
  `outbox.deliver` span per message, sent/failed/dead-letter counters,
  pending gauge) are implemented by adapters in this module, so the
  `ScanSla` / `ProcessExecutionJobs` / `DeliverOutbox` use cases stay
  telemetry-free. `SlaScanner` still opens the `sla.scan` span around
  each scan.

## Outbox Payload Typing

The outbox payload is a typed domain value object, not hand-rolled JSON:

- `domain.OutboxPayload` — record `(requestId, type, title, subject, body,
  html, step)` with factories `feed(…)` and `email(…)`. The canonical
  constructor normalizes `body` (never null) and `html` (null when blank).
- `domain.port.OutboxPayloadCodec` — encode/decode port.
- `infra.persistence.JacksonOutboxPayloadCodec` — Jackson 3 adapter; the
  encoder writes only present (non-null) fields, preserving the historical
  JSON shape.
- `application.notification.OutboxService` builds typed payloads and encodes
  them through the codec; `DeliverOutbox` decodes and routes by channel.

Configuration follows the same pattern: `EmailComposer` takes the
`application.port.FlowOpsUrls` record (bound by `config.FlowOpsUrlsConfig`)
instead of `@Value` injection.

## Domain / Persistence Separation

The domain model is **pure Java** — no `jakarta.persistence`, no Hibernate,
no Spring. Enforcement is three-layered:

1. **Module boundary** — `flowops-domain`'s pom declares only `slf4j-api`;
   an accidental JPA import fails the build.
2. **ArchUnit** — `domain_model_is_persistence_free`
   (+ `domain_model_is_spring_free`) in `ArchitectureTest`.
3. **Design** — all persistence access is behind pure-Java ports in
   `domain.port`, implemented by adapters in `infra.persistence`.

The mapping is one JPA entity per aggregate:

```
com.flowops.domain.Request          ← aggregate (state machine, invariants)
com.flowops.infra.persistence.
  entity.RequestEntity              ← @Entity(name="Request"), identical columns
  mapping.RequestMappers            ← toEntity / fromEntity, null-safe
  SpringRequestRepository           ← adapter: port methods, maps at the boundary
  RequestJpaRepository              ← Spring Data (JpaRepository<RequestEntity, Long>)
```

Conventions:

- Entities keep the **JPQL entity name** via `@Entity(name = "<Aggregate>")`
  (e.g. `UserEntity` is entity `User`), so existing `@Query` JPQL and
  Specifications work unchanged; they keep identical `@Table`/`@Column`
  mappings (JSONB via `@JdbcTypeCode(SqlTypes.JSON)`).
- Adapters persist with `saveAndFlush` and **backfill** the generated id
  (and `Request`'s `@Version`) onto the aggregate, so caller semantics are
  identical to the old single-entity world.
- `Request` has a private constructor and no state setters. Its mapper restores
  persisted state with `Request.restore(Request.Snapshot)`; the repository acknowledges
  generated ids and lock versions with `recordPersistence`. An ArchUnit rule confines
  these APIs to persistence adapters. Payload maps and nested JSON maps/lists are
  defensively copied and read-only, so edits must go through `replacePayload`.
- Other aggregates still expose a public no-arg constructor plus a
  *"Setters (persistence rehydration only)"* section used exclusively by the
  mappers; business behavior still goes through the named methods
  (`transitionTo`, `markCompleted`, …). Where a business setter already
  exists (e.g. `User.setRoles`, `RequestType.setActive`), the rehydration
  setter is named `rehydrate<Field>()` so mappers never trigger business
  side effects (timestamp bumps).

The database schema is untouched — only the Java-side mapping moved.
See `domain/package-info.java` for the full layer rules.

## Testing

- **ArchitectureTest** — 13 ArchUnit rules (above), runs in `flowops-app`
  against the full classpath of all modules
- **Unit tests** — domain logic, use cases, services
- **Integration tests** — `@SpringBootTest` + Testcontainers PostgreSQL, in
  `flowops-app` (the only module with a Spring context)
- **ConcurrentDecideTest** — SoD + optimistic locking under concurrent access

Run: `./mvnw test` (reactor build; bootable JAR in `flowops-app/target/`)

## Workflow decision policy

`domain.policy.WorkflowDecisionPolicy` owns decision effects: review-state and
supported-action validation, rejection, delegation without advancement, and approval
completion against the materialized step's threshold. It performs no I/O and returns
an explicit outcome. Approval counts supplied to it include the newly persisted decision.
Completing a step leaves the request in review until the application materializes the
next step (or discovers that none remain).

`DecideStep` retains authorization and evidence, decision persistence and next-step
materialization. After saving and flushing a fully approved request, it publishes
`RequestApproved`; synchronous subscribers compose approval notifications and enqueue
execution jobs. Rejection and delegation publish their own events for notification
handlers. Submission similarly publishes `RequestSubmitted` after saving and flushing.
All run in the existing transaction, with the request-version guard and outbox write
ordering preserved.
Rejection retains the pending step for audit compatibility. Domain tests run without
Spring or a database; integration tests cover the orchestration and concurrency.

## Synchronous mediator (incremental adoption)

`Mediator.send(Message<R>)` dispatches a command or query to exactly one
`MessageHandler<M, R>` on the caller's thread. `SynchronousMediator` discovers
Spring-managed handlers using explicit `Class<M>` registrations; it stores the
Spring proxies rather than unwrapping them. Duplicate registrations fail during
startup, missing registrations fail during dispatch, and handler exceptions pass
through unchanged. The dispatch registry contains the single unchecked cast,
backed by the handler's typed registration and exact-class lookup.

Migrated request read paths use `RequestController -> Mediator -> Handler`:

- `GET /api/v1/requests/{id}` uses `GetRequest` / `GetRequestHandler`, retaining
  owner/scoped-read permission checks against a scalar header before loading history.
- `GET /api/v1/requests` uses `ListRequests` / `ListRequestsHandler`. Listing
  permission determines whether the repository receives an own-request restriction;
  filters, sort orders and pagination pass through unchanged.
- `GET /api/v1/requests/queue` uses `GetApprovalQueue` / `GetApprovalQueueHandler`.
  Queue permission is checked before any repository reads. Caller roles and id
  constrain eligibility to pending steps on requests in review. Missing roles produce
  an empty page without querying persistence; otherwise eligibility, count and paging
  are resolved by the database through the application-owned `ApprovalQueueQueries`.

Each handler owns a read-only transaction. List and queue results use scalar
`RequestListItem` projections from application-owned `RequestListQueries` and
`ApprovalQueueQueries` ports. Both retain ordering and page totals.
`RequestDetailReader` assembles command responses; REST DTO
mapping and response contracts stay at the API edge. The persistence boundary resolves
associations, so query handlers do not force lazy initialization.

Request lifecycle endpoints also dispatch through the mediator:
`CreateDraftCommand`, `UpdateDraftCommand`, `CancelRequestCommand`,
`SubmitRequestCommand`, `DecideStepCommand` and `CloseRequestCommand` each have
one focused handler. `GetEligibleDelegatees` has a read-only query handler.
`RequestController` depends only on `Mediator`; the former `RequestService` facade
has been removed.

Command handlers own the outer write transaction through response assembly and
reuse the existing transactional use cases (which join that transaction). Submit,
decide and close retain their lifecycle observability wrappers around the use case,
followed by full detail assembly in the same transaction. Draft create/update and
cancel retain lightweight responses. Authorization, request-version checks and outbox
writes remain inside the existing use cases. Worker entry points continue to call
use cases directly. PostgreSQL integration tests inject response-assembly failures
to verify rollback of request state, steps, decisions, jobs and outbox writes.

List, queue and GET detail queries now use projection adapters described below.
Command response assembly retains its aggregate-based model with batched decisions.

The mediator is for one-handler commands and queries. Business events and durable
outbox delivery remain separate, and transactions and authorization stay explicit
in handlers rather than being inferred by the dispatcher.

## Transactional lifecycle events

`domain.event.RequestApproved` is an immutable business fact containing the saved
request id and approver username. It is separate from persisted outbox payloads.
The fully approved branch of `DecideStep` publishes it after saving and flushing.
The other lifecycle events contain only ids and immutable decision details:

| Event | Publisher | Subscribers (in order) |
|-------|-----------|------------------------|
| `RequestSubmitted` | `SubmitRequest` | `NotifyRequestSubmitted`, `NotifySubmittedRequestApprovers` |
| `RequestRejected` | `DecideStep` | `NotifyRequestRejected` |
| `DecisionDelegated` | `DecideStep` | `NotifyDecisionDelegated` |

Submission first writes requester notifications (order 100), then eligible-approver
notifications (order 200) when the saved request remains in review. Submission with
all steps skipped retains its existing behavior: it emits only `RequestSubmitted`,
without triggering approval notifications or execution jobs. Rejection preserves its
justification and requester recipient; delegation preserves the recorded step code,
delegator username, delegatee recipient and existing email wording. Their notification
handlers run at order 100. Notification payloads, keys and null correlation-id arguments
are unchanged. Notifications for the next step after an approval still use the existing
`ApproverNotifier` call in `DecideStep`.

`DomainEventPublisher` fans out to all matching `DomainEventHandler<E>` subscribers.
`SynchronousDomainEventPublisher` invokes Spring-managed handler proxies directly on
the caller's thread, independent of Spring's configurable event multicaster. Its
`publish` method requires an existing transaction (`MANDATORY`); there is no async or
after-commit delivery. Missing subscribers fail explicitly. Handler exceptions stop
fan-out and propagate, marking the enclosing transaction for rollback.

Spring's ordered handler list preserves the previous sequence:

1. `NotifyRequestApproved` (order 100) loads the saved request, composes the existing
   approval email, and writes FEED and EMAIL outbox records with the existing keys.
2. `EnqueueApprovedRequestJobs` (order 200) loads the saved request and delegates to
   the existing idempotent execution-job enqueueing service.

Subscribers resolve the request within the same transaction rather than sharing a
mutable aggregate in the event. This adds two repository lookups on final approval
and submission, and one on rejection or delegation.
The request state, decision history, step changes, jobs and outbox entries commit or
roll back together. Existing durable workers continue delivery after commit.

Tests cover synchronous multi-handler dispatch, exception propagation, transaction
requirements and replay idempotency. A PostgreSQL test installs a third subscriber
that fails after verifying notification and job writes, then verifies that the full
approval transaction rolled back. Parameterized PostgreSQL tests exercise successful
commit, replay idempotency and late-subscriber rollback for submission, rejection and
delegation, including rollback of submission's eligible-approver notifications.

## Request list projection

`ListRequestsHandler` checks listing permission, then calls the application-owned
`RequestListQueries` port with the actual caller id and a separate list-all flag.
The port returns `PageResult<RequestListItem>` without domain request aggregates.
The former filtered `findAll` operation has been removed from `RequestRepository`;
aggregate loading for commands is unchanged; GET detail uses its own query port.

`JpaRequestListQueries` uses a Criteria constructor projection selecting only the
request fields required by the existing list response, type code/name, optional
department code and requester id/username/display name. It never selects entities or
calls aggregate mappers. A left join preserves requests whose type has no department.
The existing `RequestSpecifications` applies status/type/owner filters to both the
projection and count queries before pagination; allowed sort fields match the API.
The adapter performs one page query and one count query. Authorization's own user and
permission lookups are separate from these two queries.

`RequestResponse.from(RequestListItem)` preserves the HTTP response shape, including
empty steps, clarifications and execution-job arrays. PostgreSQL tests compare it with
the aggregate-based response and verify JSON, nullable joins, sorting, visibility and
page totals. Hibernate statistics verify two statements and zero entity/collection
loads for the projection operation.

The migration also fixes `mine=true` for callers with listing permission: the previous
handler passed a null caller id for these callers, so the owner filter returned no
matches. The new port always receives the caller id, allowing the existing owner
predicate to work for both own-only and list-all callers.

## Approval queue projection

`GetApprovalQueueHandler` authorizes queue access, then supplies caller id and roles
to `ApprovalQueueQueries`. `JpaApprovalQueueQueries` selects request rows with an
`EXISTS` predicate for a pending step whose required role matches or which has a
DELEGATE decision addressed to the caller. The delegation must reference that same
pending step instance. The request must be in review and belong to someone else.
The existing role-less-caller empty-queue behavior is preserved.

Eligibility filters apply before ordering, paging and counting. Correlated `EXISTS`
predicates prevent duplicates from multiple matching steps or delegation records.
There is no intermediate eligible-id list, unbounded `IN` clause or aggregate mapping.
List and queue adapters share `RequestListProjection` for the scalar page and count
queries. The old queue methods have been removed from request and step-instance
repositories. A page uses two SQL statements (page and total count); eligibility is
part of each statement rather than a separate prefetch.

PostgreSQL tests cover role matches, delegation on the current pending instance,
requester exclusion, request/step states, duplicate matches, sorted pages and totals.
Statistics checks verify no entity loads and two statements for the queue projection.

## Public pagination convention

Every paginated HTTP endpoint uses one-based page numbers: omitted `page` defaults to
1, `page=1` returns the first page and `page=2` returns the second. Page numbers below
1 return HTTP 400. This intentionally replaces the previous zero-based API contract;
clients sending `page=0` must switch to `page=1`. Default page size remains 20, with
existing size normalization to the range 1–100.

`api.web.Pagination` converts public page numbers to zero-based `PageQuery` values,
validates the page and applies canonical whitelisted sort fields. `PageResponse.from`
converts internal page numbers back to one-based values; first/last and total-page
semantics remain unchanged, including empty and out-of-range pages. Notification
feeds, including their manually paged unread path, use the same conversion and a
long offset to avoid multiplication overflow. All application and persistence paging
continues to use zero-based numbers.

API contract tests cover defaults, explicit first/second pages and invalid page values
across users, departments, roles, permissions, workflows, request types, requests,
approval queues and both notification feed variants.

## Request detail projections and decision batching

`GetRequestHandler` uses the application-owned `RequestDetailQueries` port. It loads
only a scalar `RequestListItem` header first, builds the same authorization target
(including payload amount), and enforces owner/scoped-read permission before loading
steps, decisions, clarifications or jobs. Missing requests retain the existing 404
behavior; denied reads never load detail collections.

`JpaRequestDetailQueries` returns `RequestDetailView`, whose nested records contain
only scalar values, JSON and enums. The request header reuses the list projection.
Separate projections load steps ordered by id, all request decisions ordered by id,
clarifications by creation time descending and jobs by creation time ascending.
Decisions are grouped by step-instance id without changing their order. Historical
actor usernames and authorization evidence come directly from the decision rows.
No entity or aggregate mapping is used, and DTO conversion remains at the API edge.

The detail adapter uses five SQL statements (header, steps, batched decisions,
clarifications, jobs), independent of step count. When there are no steps it skips
the decision query, reducing this to four. Authorization's user/permission lookup
is separate. PostgreSQL tests compare the scalar response to aggregate-based response
assembly with one and four steps, full audit data, answered/unanswered clarifications,
and planned/completed jobs; they verify fixed query counts and zero entity loads.

Command handlers still return `RequestDetail` and use `RequestDetailReader` inside
the write transaction. That reader now calls `DecisionRepository.findByRequestIdOrderById`
once and groups decisions across all loaded steps, replacing one decision query per
step. Steps without decisions retain empty lists. A focused test verifies interleaved
history grouping, ordering and the single batch read. Existing transaction rollback
and API tests continue to protect command behavior and response shapes.
