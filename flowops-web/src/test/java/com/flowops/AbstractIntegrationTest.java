package com.flowops;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests: spins up a PostgreSQL container
 * and points the Spring context at it (Flyway runs the real migrations).
 *
 * The container is started eagerly in a static initializer instead of via
 * {@code @Container}: with static fields, JUnit's Testcontainers extension
 * calls start/stop per test-class lifecycle, which restarted the container
 * (new port) between test classes and left the cached Spring context with a
 * dead datasource. Owning the lifecycle ourselves keeps one stable container
 * for the whole surefire JVM, and Testcontainers' shutdown hook cleans it up
 * when the JVM exits.
 */
@SpringBootTest
@org.springframework.context.annotation.Import(AbstractIntegrationTest.ManualWorkers.class)
public abstract class AbstractIntegrationTest {

    /** Every cached context shares the database; pollers must be stopped before scheduling starts. */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class ManualWorkers {
        @org.springframework.context.annotation.Bean
        static org.springframework.beans.factory.config.BeanPostProcessor stopAutomaticPolling() {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessBeforeInitialization(Object bean, String name) {
                    if (bean instanceof com.flowops.worker.IdentityReconciliationWakeupWorker immediate) immediate.setRunning(false);
                    if (bean instanceof com.flowops.worker.EligibilityReconciler eligibility) eligibility.setRunning(false);
                    if (bean instanceof com.flowops.worker.CompletionReconciler completion) completion.setRunning(false);
                    if (bean instanceof com.flowops.worker.JobWorker jobs) jobs.setRunning(false);
                    if (bean instanceof com.flowops.worker.OutboxDispatcher outbox) outbox.setRunning(false);
                    if (bean instanceof com.flowops.worker.SlaScanner sla) sla.setRunning(false);
                    return bean;
                }
            };
        }
    }

    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("flowops")
                .withUsername("flowops")
                .withPassword("flowops");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Cached Spring contexts share one PostgreSQL instance; avoid ten idle clients per context.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
    }
}
