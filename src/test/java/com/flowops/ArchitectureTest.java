package com.flowops;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture guardrails (DDD / Clean Architecture, single-module variant).
 *
 * <p>Dependency rule enforced here:
 * <pre>
 *   domain  ←  application (service)  ←  api
 *     ↑__________________________________________  (infra adapters live outside domain)
 * </pre>
 *
 * <p>These rules codify the target architecture from the refactor plan and
 * will tighten as phases 2–4 move repositories/adapters into {@code infra}
 * and split {@code service} into use cases + adapters.
 */
@AnalyzeClasses(packages = "com.flowops", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** The domain model is the core — it must not reach out to the API layer. */
    @ArchTest
    static final ArchRule domain_must_not_depend_on_api =
            noClasses().that().resideInAPackage("com.flowops.domain..")
                    .should().dependOnClassesThat().resideInAPackage("com.flowops.api..");

    /** Domain logic lives in the model, not in services. Services depend on the domain, never the reverse. */
    @ArchTest
    static final ArchRule domain_must_not_depend_on_service =
            noClasses().that().resideInAPackage("com.flowops.domain..")
                    .should().dependOnClassesThat().resideInAPackage("com.flowops.service..");

    /** The domain must stay free of cross-cutting infrastructure concerns. */
    @ArchTest
    static final ArchRule domain_must_not_depend_on_infrastructure =
            noClasses().that().resideInAPackage("com.flowops.domain..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.flowops.security..",
                            "com.flowops.infra..",
                            "com.flowops.observability..",
                            "com.flowops.authz..");

    /** Controllers/DTOs must not touch persistence directly. */
    @ArchTest
    static final ArchRule api_must_not_use_repositories =
            noClasses().that().resideInAPackage("com.flowops.api..")
                    .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");

    /**
     * Domain model classes are Spring-free. JPA annotations are tolerated for now
     * (pragmatic stance for Spring + JPA at this project size); Spring Data
     * repositories and Specifications are excluded — they migrate to
     * {@code infra.persistence} in Phase 2, at which point this rule can be
     * widened to cover the whole domain package.
     */
    @ArchTest
    static final ArchRule domain_model_is_spring_free =
            noClasses().that().resideInAPackage("com.flowops.domain..")
                    .and().haveNameNotMatching(".*Repository|.*Specifications")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework..");

    /** Services implement use cases against the domain — they must not know about the API layer. */
    @ArchTest
    static final ArchRule service_must_not_depend_on_api =
            noClasses().that().resideInAPackage("com.flowops.service..")
                    .should().dependOnClassesThat().resideInAPackage("com.flowops.api..");
}
