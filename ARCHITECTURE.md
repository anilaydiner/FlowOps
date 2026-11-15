# FlowOps — Architecture

Clean Architecture with DDD rich domain model.
Layer boundaries are enforced **twice**: structurally (4 Maven modules — an
illegal dependency is a compile error) and by `ArchitectureTest`
(11 ArchUnit rules for the seams Maven can't see).

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
│  ├── request/         RequestService, ClarificationService                      │
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
  `RequestService` calls — no `io.opentelemetry` / `io.micrometer` types in
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
- Aggregates expose a public no-arg constructor plus a
  *"Setters (persistence rehydration only)"* section used exclusively by the
  mappers; business behavior still goes through the named methods
  (`transitionTo`, `markCompleted`, …). Where a business setter already
  exists (e.g. `User.setRoles`, `RequestType.setActive`), the rehydration
  setter is named `rehydrate<Field>()` so mappers never trigger business
  side effects (timestamp bumps).

The database schema is untouched — only the Java-side mapping moved.
See `domain/package-info.java` for the full layer rules.

## Testing

- **ArchitectureTest** — 12 ArchUnit rules (above), runs in `flowops-app`
  against the full classpath of all modules
- **Unit tests** — domain logic, use cases, services
- **Integration tests** — `@SpringBootTest` + Testcontainers PostgreSQL, in
  `flowops-app` (the only module with a Spring context)
- **ConcurrentDecideTest** — SoD + optimistic locking under concurrent access

Run: `./mvnw test` (reactor build; bootable JAR in `flowops-app/target/`)
