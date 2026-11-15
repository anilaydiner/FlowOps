# FlowOps — Architecture

Clean Architecture (single-module variant) with DDD rich domain model.
All dependency rules are **enforced by `ArchitectureTest`** (11 ArchUnit rules).

## Layer Dependency Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│  api/                                                                          │
│  ├── *Controller.java    REST endpoints, DTO mapping, auth projection           │
│  ├── dto/                Request/Response DTOs (20 records, mapping-only)       │
│  └── web/                HTTP edge: RFC 7807 exception handlers, ProblemDetails │
│                          advice, CorrelationIdFilter, ApiErrors (owner of the   │
│                          urn:flowops:error:* namespace)                         │
│                                                                        │       │
│  depends on:  domain (DTO mapping) + application (service calls)          │       │
└──────────────────────────────────────────────────────────────────────────────────┘
         │                                          │
         ▼                                          ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│  application/                                                                   │
│  ├── usecase/         One class per workflow action                             │
│  │    CreateDraft, UpdateDraft, CancelRequest, SubmitRequest,                  │
│  │    DecideStep, CloseRequest, StepMaterializer                               │
│  ├── service/         Application services (orchestration + read models)        │
│  │    RequestService, EmailComposer, AuditService, ClarificationService,       │
│  │    UserService, NotificationService, OutboxService, ExecutionService,       │
│  │    WorkflowService, RequestTypeService, DepartmentService,                  │
│  │    RoleService, PermissionService, DomainServiceConfig                      │
│  ├── port/            Driven-side ports (RequestLifecycleObservability —       │
│  │                    implemented by the observability module)                 │
│  └── model/           Read models (RequestDetail, WorkflowView)                │
│                                                                        │       │
│  depends on:  domain only (entities, ports, services, exceptions)      │       │
└──────────────────────────────────────────────────────────────────────────────────┘
         │
         ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│  domain/          THE CORE — pure Java + JPA annotations (see trade-off note)   │
│  ├── (root)         Entities: Request, Workflow, WorkflowStep, StepInstance,    │
│  │                  Decision, Clarification, User, Role, Permission,            │
│  │                  Department, RequestType, ExecutionJob, OutboxMessage,       │
│  │                  Notification, EmailLogEntry                                 │
│  ├── exception/     BusinessRuleViolation, NotFound, Forbidden, …              │
│  ├── identity/      CallerIdentity (framework-free identity record)             │
│  ├── paging/        PageQuery, PageResult (framework-free paging twins)         │
│  ├── policy/        AuthorizationEvaluator, PermissionCodes, TargetContext,     │
│  │                  AuthorizationDecision                                       │
│  ├── port/          Pure-Java repository ports (12 interfaces)                  │
│  ├── query/         Filter objects (RequestFilter, WorkflowFilter, …)          │
│  └── service/       Domain services (PayloadValidator, ConditionEvaluator)      │
│                                                                        │       │
│  depends on:  JDK + jakarta.persistence annotations ONLY              │       │
│  forbidden:   Spring, security, infra, observability, application, api  │       │
└──────────────────────────────────────────────────────────────────────────────────┘
         ▲
         │  implements ports
┌──────────────────────────────────────────────────────────────────────────────────┐
│  infra/                                                                       │
│  ├── persistence/     *JpaRepository (Spring Data) + @Repository adapters       │
│  │                   (SpringWorkflowRepository, SpringStepInstanceRepository,  │
│  │                   …) + Paging translator + Specifications                    │
│  ├── mail/            SMTP sender (implements domain.mail port)                │
│  ├── action/          External action adapters (execution jobs)                │
│  └── worker/          Scheduled workers (SlaScanner, OutboxDispatcher, …)      │
│                                                                        │       │
│  depends on:  domain (entities + ports) + Spring + infra libraries    │       │
└──────────────────────────────────────────────────────────────────────────────────┘

  security/        JWT filter, UserPrincipal (projects to CallerIdentity)
                   depends on: domain.identity + api.web.ApiErrors (error URIs)
                   forbidden:   application

  observability/   CorrelationId, FlowOpsSpans (OTel helper), TracingConfig,
                   SpanNames/MetricNames registries,
                   RequestLifecycleObservabilityAdapter (implements the
                   application port)
                   depends on:  application.port + domain (read-only) + OTel/Micrometer

  config/          OpenApiConfig (Spring Bean wiring for API docs)
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
| 11 | `infra.worker` must not depend on `application.service` | Workers are thin shells over use cases (one known legacy exclusion) |

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

- `api/web/ApiErrors` owns the RFC 7807 `type` namespace (`urn:flowops:error:*`);
  the MVC exception handlers, the `ProblemDetails` advice, and the security
  filter chain (401/403) all derive their `type` URIs from it.
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
  │
  │ delegates to
  ▼
infra/persistence/RequestJpaRepository.java      ← Spring Data JPA repository
```

12 ports, 12 Spring Data repositories, 12 `@Repository` adapters.

Idempotent writes go through port methods like `saveIfAbsent(…)` — the
duplicate-key handling (Spring `DataIntegrityViolationException`) is caught
inside the adapter, so application code never sees Spring exception types.

## Domain Services

| Service | Location | Spring? | Purpose |
|---------|----------|---------|---------|
| `PayloadValidator` | `domain.service` | No | Validates request payload against type schema |
| `ConditionEvaluator` | `domain.service` | No | Evaluates workflow step conditions (SpEL) |
| `AuthorizationEvaluator` | `domain.policy` | No | Scoped permissions, SoD, evidence trail |

Wired as beans via `application.service.DomainServiceConfig` (`@Configuration`).

## Observability

- Lifecycle spans + counters (PLAN §8) are emitted by
  `observability.RequestLifecycleObservabilityAdapter`, which implements the
  framework-free `application.port.RequestLifecycleObservability` port that
  `RequestService` calls — no `io.opentelemetry` / `io.micrometer` types in
  the application layer.
- `observability.FlowOpsSpans` wraps span start/status/end; `SpanNames` and
  `MetricNames` are the shared name registries.
- Workers (`infra/worker`) open their own spans (`job.execute`,
  `outbox.deliver`, `sla.scan`) and record the job/outbox/SLA metrics.

## JPA Annotation Trade-off

Domain entities carry `jakarta.persistence` annotations as a pragmatic
single-module decision. The domain is still **logically** Spring-free:
no Spring imports, no Spring types, no Spring behaviour. Persistence
knowledge is confined to annotation metadata consumed by the adapter.

The migration path to full Option B (separate JPA entities + mappers)
is available without touching ports, use-cases, or API code — see
`domain/package-info.java` for details.

## Testing

- **ArchitectureTest** — 11 ArchUnit rules (above)
- **Unit tests** — domain logic, use cases, services
- **Integration tests** — `@SpringBootTest` + Testcontainers PostgreSQL
- **ConcurrentDecideTest** — SoD + optimistic locking under concurrent access

Run: `./mvnw test`
