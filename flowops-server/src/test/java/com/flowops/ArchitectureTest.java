package com.flowops;

import com.flowops.domain.request.Request;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Dependency guardrails across domain, application, infrastructure and server modules. */
@AnalyzeClasses(packages = "com.flowops", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    private static final String[] INFRASTRUCTURE = {
            "com.flowops.persistence..", "com.flowops.mail..", "com.flowops.action..",
            "com.flowops.worker..", "com.flowops.security..", "com.flowops.observability.."
    };
    // HTTP adapters still project UserPrincipal and use JwtService at the security edge.
    private static final String[] NON_SECURITY_ADAPTERS = {
            "com.flowops.persistence..", "com.flowops.mail..", "com.flowops.action..",
            "com.flowops.worker..", "com.flowops.observability.."
    };
    private static final Set<String> WORKER_OPERATIONS = Set.of(
            "com.flowops.application.request.sla.ScanSla",
            "com.flowops.application.execution.ProcessExecutionJobs",
            "com.flowops.application.notification.outbox.DeliverOutbox");

    @ArchTest
    static final ArchRule request_restoration_is_confined_to_persistence =
            noClasses().that().resideOutsideOfPackage("com.flowops.persistence..")
                    .should().callMethod(Request.class, "restore", Request.Snapshot.class)
                    .orShould().callMethod(Request.class, "recordPersistence", Long.class, Long.class);

    @ArchTest
    static final ArchRule domain_must_not_depend_on_api =
            noClasses().that().resideInAPackage("com.flowops.domain..")
                    .should().dependOnClassesThat().resideInAPackage("com.flowops.api..");

    @ArchTest
    static final ArchRule domain_must_not_depend_on_application =
            noClasses().that().resideInAPackage("com.flowops.domain..")
                    .should().dependOnClassesThat().resideInAPackage("com.flowops.application..");

    @ArchTest
    static final ArchRule domain_must_not_depend_on_infrastructure =
            noClasses().that().resideInAPackage("com.flowops.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(INFRASTRUCTURE);

    @ArchTest
    static final ArchRule api_must_not_depend_on_infra =
            noClasses().that().resideInAPackage("com.flowops.api..")
                    .should().dependOnClassesThat().resideInAnyPackage(NON_SECURITY_ADAPTERS);

    @ArchTest
    static final ArchRule domain_model_is_spring_free =
            noClasses().that().resideInAPackage("com.flowops.domain..")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework..");

    @ArchTest
    static final ArchRule domain_model_is_persistence_free =
            noClasses().that().resideInAPackage("com.flowops.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage("jakarta.persistence..", "org.hibernate..");

    @ArchTest
    static final ArchRule application_must_not_depend_on_api =
            noClasses().that().resideInAPackage("com.flowops.application..")
                    .should().dependOnClassesThat().resideInAPackage("com.flowops.api..");

    @ArchTest
    static final ArchRule application_must_not_depend_on_security =
            noClasses().that().resideInAnyPackage("com.flowops.application..", "com.flowops.domain..")
                    .should().dependOnClassesThat().resideInAPackage("com.flowops.security..");

    @ArchTest
    static final ArchRule application_must_not_depend_on_infra =
            noClasses().that().resideInAPackage("com.flowops.application..")
                    .should().dependOnClassesThat().resideInAnyPackage(INFRASTRUCTURE);

    @ArchTest
    static final ArchRule application_must_not_use_spring_dao_orm_data_web_scheduling =
            noClasses().that().resideInAPackage("com.flowops.application..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework.dao..", "org.springframework.orm..", "org.springframework.data..",
                            "org.springframework.web..", "org.springframework.scheduling..");

    @ArchTest
    static final ArchRule telemetry_is_confined_to_observability_and_infra =
            noClasses().that().resideOutsideOfPackages(NON_SECURITY_ADAPTERS)
                    .should().dependOnClassesThat().resideInAnyPackage("io.micrometer..", "io.opentelemetry..");

    @ArchTest
    static final ArchRule infra_worker_may_only_depend_on_use_cases_and_ports =
            noClasses().that().resideInAPackage("com.flowops.worker..")
                    .should().dependOnClassesThat(DescribedPredicate.describe(
                            "application classes other than worker operations or ports", c -> {
                                String pkg = c.getPackageName();
                                boolean allowed = pkg.endsWith(".port") || pkg.contains(".port.")
                                        || WORKER_OPERATIONS.stream().anyMatch(name ->
                                                c.getName().equals(name) || c.getName().startsWith(name + "$"));
                                return pkg.startsWith("com.flowops.application.") && !allowed;
                            }));

    @ArchTest
    static final ArchRule delivery_contracts_and_models_are_application_owned =
            classes().that(DescribedPredicate.describe("email and outbox delivery contracts, records and operations",
                            c -> Set.of("EmailGateway", "EmailLogEntry", "EmailLogEntryRepository",
                                    "OutboxMessage", "OutboxPayload", "OutboxStatus", "OutboxEvents",
                                    "OutboxMessageRepository", "OutboxPayloadCodec", "OutboxObservability",
                                    "OutboxService", "DeliverOutbox").contains(c.getSimpleName())))
                    .should().resideInAPackage("com.flowops.application.notification..");

    @ArchTest
    static final ArchRule obsolete_layer_packages_must_not_return =
            noClasses().should().resideInAnyPackage(
                    "com.flowops.infra..", "com.flowops.application.usecase..", "com.flowops.application.model..",
                    "com.flowops.application.org..", "com.flowops.application.port..", "com.flowops.domain.port..",
                    "com.flowops.domain.policy..", "com.flowops.domain.query..", "com.flowops.domain.service..");
}
