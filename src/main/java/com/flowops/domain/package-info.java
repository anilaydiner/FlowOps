/**
 * Domain model — the core of FlowOps.
 *
 * <h2>Layer rules (enforced by {@code ArchitectureTest})</h2>
 * <ul>
 *   <li>May depend on: the JDK and JPA annotations only.</li>
 *   <li>Must NOT depend on: Spring, security, infra, observability, application, api.</li>
 * </ul>
 *
 * <h2>JPA annotation trade-off</h2>
 * <p>
 * Entity classes in this package carry {@code jakarta.persistence} annotations
 * ({@code @Entity}, {@code @Table}, {@code @Column}, etc.) as a pragmatic
 * single-module decision. A strict DDD split would keep the domain model
 * annotation-free and project it onto separate JPA entities via mappers
 * ("Option B"), at roughly 2× the code surface.
 * </p>
 * <p>
 * The architectural boundary is still enforced:
 * </p>
 * <ul>
 *   <li>Persistence access is behind pure-Java ports in {@code domain.port}.</li>
 *   <li>Spring Data implementations live in {@code infra.persistence}.</li>
 *   <li>{@code domain} must not import any {@code org.springframework.*} class
 *       (ArchUnit rule {@code domain_model_is_spring_free}).</li>
 * </ul>
 * <p>
 * The annotations are declarative metadata consumed by the persistence
 * adapter — they do not make the domain <em>logically</em> depend on Spring.
 * If the codebase grows beyond a single service, the natural migration path
 * is extracting the annotations into JPA entities and introducing mappers
 * without changing any port, use-case, or API code.
 * </p>
 *
 * <h2>Package layout</h2>
 * <pre>
 * domain/
 *   ├── (root)          Entities &amp; value objects (Request, Workflow, StepInstance, …)
 *   ├── exception/      Domain exceptions (BusinessRuleViolation, NotFound, …)
 *   ├── identity/       CallerIdentity — framework-free identity record
 *   ├── paging/         PageQuery / PageResult — framework-free paging twins
 *   ├── policy/         AuthorizationEvaluator, PermissionCodes, TargetContext
 *   ├── port/           Pure-Java repository ports (implemented in infra.persistence)
 *   ├── query/          Filter objects (RequestFilter, WorkflowFilter, …)
 *   └── service/        Domain services (PayloadValidator, ConditionEvaluator)
 * </pre>
 */
package com.flowops.domain;
