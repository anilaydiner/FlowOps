/**
 * Domain model — the core of FlowOps.
 *
 * <h2>Layer rules (enforced by {@code ArchitectureTest})</h2>
 * <ul>
 *   <li>May depend on: the JDK and slf4j only.</li>
 *   <li>Must NOT depend on: Spring, JPA ({@code jakarta.persistence}),
 *       Hibernate ({@code org.hibernate}), security, infra, observability,
 *       application, api.</li>
 * </ul>
 *
 * <h2>Persistence separation</h2>
 * <p>
 * The aggregates in this package are pure Java — no persistence annotations.
 * Their JPA mapping lives in {@code infra.persistence.entity}
 * ({@code RequestEntity}, {@code UserEntity}, …), one entity per aggregate,
 * with an identical column mapping. {@code infra.persistence.mapping}
 * ({@code RequestMappers}, …) converts aggregate ↔ entity at the adapter
 * boundary; adapters implement the pure-Java ports in {@code domain.port}.
 * </p>
 * <p>
 * Aggregates therefore expose a public no-arg constructor and a
 * "rehydration only" setter section used exclusively by those mappers.
 * Business behavior goes through the named methods
 * ({@code Request.transitionTo}, {@code ExecutionJob.markCompleted}, …);
 * business code should never construct an aggregate blank or use the
 * rehydration setters.
 * </p>
 * <p>
 * The architectural boundary is enforced three ways:
 * </p>
 * <ul>
 *   <li>Module boundaries — {@code flowops-domain} declares no
 *       {@code jakarta.persistence} or {@code org.hibernate} dependency, so
 *       any accidental import fails the build.</li>
 *   <li>ArchUnit rule {@code domain_model_is_persistence_free} in
 *       {@code ArchitectureTest}.</li>
 *   <li>Persistence access is behind pure-Java ports in {@code domain.port},
 *       implemented in {@code infra.persistence}.</li>
 * </ul>
 *
 * <h2>Package layout</h2>
 * <pre>
 * domain/
 *   ├── (root)          Aggregates &amp; value objects (Request, Workflow, StepInstance, …)
 *   ├── exception/      Domain exceptions (InvalidStateTransition, NotFound, …)
 *   ├── identity/       CallerIdentity — framework-free identity record
 *   ├── paging/         PageQuery / PageResult — framework-free paging twins
 *   ├── policy/         AuthorizationEvaluator, PermissionCodes, TargetContext
 *   ├── port/           Pure-Java repository ports (implemented in infra.persistence)
 *   ├── query/          Filter objects (RequestFilter, WorkflowFilter, …)
 *   └── service/        Domain services (PayloadValidator, ConditionEvaluator)
 * </pre>
 */
package com.flowops.domain;
