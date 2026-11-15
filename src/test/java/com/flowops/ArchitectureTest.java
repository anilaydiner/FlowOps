package com.flowops;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture guardrails (DDD / Clean Architecture, single-module variant).
 *
 * <p>Dependency rule enforced here (all arrows point inward):
 * <pre>
 *   domain  ←  application {model, service, usecase}  ←  api
 *     ↑______________________________  (infra adapters implement domain ports)
 * </pre>
 *
 * <ul>
 *   <li>{@code domain} — pure Java: entities, value objects, policy, services,
 *       ports. No Spring, no security, no infra, no api.</li>
 *   <li>{@code application.model} — read models (RequestDetail, WorkflowView).</li>
 *   <li>{@code application.service} — application services orchestrating use cases.</li>
 *   <li>{@code application.usecase} — one class per workflow use case (submit,
 *       decide, close, drafts…).</li>
 *   <li>{@code api} — controllers + DTOs; projects the security principal into
 *       {@code domain.identity.CallerIdentity}.</li>
 *   <li>{@code infra} — persistence adapters, workers, HTTP cross-cutting.</li>
 *   <li>{@code security} — JWT filter/manager; may project into the domain,
 *       never the reverse.</li>
 * </ul>
 */
@AnalyzeClasses(packages = "com.flowops", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** The domain model is the core — it must not reach out to the API layer. */
    @ArchTest
    static final ArchRule domain_must_not_depend_on_api =
            noClasses().that().resideInAPackage("com.flowops.domain..")
                    .should().dependOnClassesThat().resideInAPackage("com.flowops.api..");

    /** Domain logic lives in the model, not in the application layer — which depends on the domain, never the reverse. */
    @ArchTest
    static final ArchRule domain_must_not_depend_on_application =
            noClasses().that().resideInAPackage("com.flowops.domain..")
                    .should().dependOnClassesThat().resideInAPackage("com.flowops.application..");

    /** The domain must stay free of cross-cutting infrastructure concerns. */
    @ArchTest
    static final ArchRule domain_must_not_depend_on_infrastructure =
            noClasses().that().resideInAPackage("com.flowops.domain..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.flowops.security..",
                            "com.flowops.infra..",
                            "com.flowops.observability..");

    /**
     * The API layer (controllers + DTOs) orchestrates through the application
     * layer only. It must not import infrastructure adapters, workers, or
     * persistence internals. (Domain imports are allowed — DTO mapping needs
     * the aggregate types.)
     */
    @ArchTest
    static final ArchRule api_must_not_depend_on_infra =
            noClasses().that().resideInAPackage("com.flowops.api..")
                    .should().dependOnClassesThat().resideInAPackage("com.flowops.infra..");

    /**
     * Domain model classes are Spring-free — no Spring Data repositories,
     * no Spring annotations, no Spring types. All persistence is behind
     * pure-Java ports in {@code domain.port}, implemented by adapters
     * in {@code infra.persistence}.
     */
    @ArchTest
    static final ArchRule domain_model_is_spring_free =
            noClasses().that().resideInAPackage("com.flowops.domain..")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework..");

    /** The application layer orchestrates the domain — it must not know about the API layer. */
    @ArchTest
    static final ArchRule application_must_not_depend_on_api =
            noClasses().that().resideInAPackage("com.flowops.application..")
                    .should().dependOnClassesThat().resideInAPackage("com.flowops.api..");

    /**
     * Application services and use cases never touch security types. The API edge
     * projects the security principal into {@code domain.identity.CallerIdentity};
     * application code and domain policy consume only that framework-free identity.
     */
    @ArchTest
    static final ArchRule application_must_not_depend_on_security =
            noClasses().that().resideInAnyPackage("com.flowops.application..", "com.flowops.domain..")
                    .should().dependOnClassesThat().resideInAPackage("com.flowops.security..");

    /** The application layer stays framework-agnostic — no infrastructure adapters (persistence, workers, SMTP…). */
    @ArchTest
    static final ArchRule application_must_not_depend_on_infra =
            noClasses().that().resideInAPackage("com.flowops.application..")
                    .should().dependOnClassesThat().resideInAPackage("com.flowops.infra..");

    /**
     * Application code must not reach into Spring persistence, web, or
     * scheduling APIs — those are owned by infra adapters. (Spring exception
     * types that used to leak into services are now caught behind repository
     * ports, e.g. {@code saveIfAbsent}.)
     */
    @ArchTest
    static final ArchRule application_must_not_use_spring_dao_orm_data_web_scheduling =
            noClasses().that().resideInAPackage("com.flowops.application..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework.dao..",
                            "org.springframework.orm..",
                            "org.springframework.data..",
                            "org.springframework.web..",
                            "org.springframework.scheduling..");

    /**
     * Telemetry (OpenTelemetry, Micrometer) lives in the observability module
     * and the infra adapters that consume it — never in domain, application,
     * api, or security code.
     */
    @ArchTest
    static final ArchRule telemetry_is_confined_to_observability_and_infra =
            noClasses().that().resideOutsideOfPackages("com.flowops.infra..", "com.flowops.observability..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.micrometer..", "io.opentelemetry..");

    /**
     * Workers are thin infra shells: they orchestrate use cases, not
     * application services. ({@code SlaScanner → OutboxService} is a known
     * legacy violation, removed when the SLA scan becomes a use case.)
     */
    @ArchTest
    static final ArchRule infra_worker_must_not_depend_on_application_services =
            noClasses().that().resideInAPackage("com.flowops.infra.worker..")
                    .and().haveNameNotMatching("com\\.flowops\\.infra\\.worker\\.SlaScanner(\\..*)?")
                    .should().dependOnClassesThat().resideInAPackage("com.flowops.application.service..");
}
