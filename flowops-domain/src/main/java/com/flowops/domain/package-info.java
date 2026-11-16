/**
 * Pure business core, grouped by capability: request, workflow, identity,
 * organization, execution, notification and audit. Capability-local port packages
 * define persistence contracts implemented by the infrastructure module.
 *
 * <p>Domain classes depend only on the JDK and slf4j. Spring, JPA, Hibernate,
 * application services and infrastructure adapters are excluded by Maven module
 * boundaries and ArchitectureTest. JPA entities and mappers live in
 * com.flowops.persistence.entity and com.flowops.persistence.mapping.
 *
 * <p>Request exposes explicit restoration and persistence acknowledgement APIs,
 * restricted to persistence adapters by ArchUnit. Other aggregates retain their
 * existing restoration setters; business operations use their named methods.
 * Shared paging, exception and event-marker packages remain at the domain root.
 */
package com.flowops.domain;
