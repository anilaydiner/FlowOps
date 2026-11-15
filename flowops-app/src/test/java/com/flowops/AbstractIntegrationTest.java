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
public abstract class AbstractIntegrationTest {

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
    }
}
