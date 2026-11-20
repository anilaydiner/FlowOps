package com.flowops;

import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.junit.jupiter.api.Assertions.*;

class FreshDatabaseMigrationTest extends AbstractIntegrationTest {

    @Autowired
    DataSource dataSource;

    @Test
    void freshBootstrapInitializesCatalogGuardsAndEmptyApplicationHistory() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String fresh = "fresh_" + suffix;
        try (var connection = dataSource.getConnection()) {
            String original = connection.getSchema();
            var isolated = new SingleConnectionDataSource(connection, true);
            var jdbc = new JdbcTemplate(isolated);
            try {
                var bootstrap = Flyway.configure().dataSource(isolated).schemas(fresh).defaultSchema(fresh).load();
                assertEquals(3, bootstrap.migrate().migrationsExecuted);
                bootstrap.validate();
                assertEquals(0, bootstrap.migrate().migrationsExecuted);
                connection.setSchema(fresh);
                assertEquals(List.of("1", "2", "3"), jdbc.queryForList(
                        "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank",
                        String.class));
                assertEquals(List.of("admin", "alice"),
                        jdbc.queryForList("SELECT username FROM users ORDER BY username", String.class));
                assertEquals(List.of("EQUIPMENT", "EXPENSE", "IT_ACCESS", "POLICY_EXCEPTION"),
                        jdbc.queryForList("SELECT code FROM request_types ORDER BY code", String.class));
                assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM workflows", Integer.class));
                assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM departments", Integer.class));
                assertEquals(1, jdbc.queryForObject(
                        "SELECT count(*) FROM role_permissions rp JOIN permissions p ON p.id=rp.permission_id JOIN roles r ON r.id=rp.role_id WHERE r.code='ADMIN' AND p.code='execution:plan:dispose' AND p.scope_type IS NULL",
                        Integer.class));
                for (String table : List.of("requests", "decisions", "step_instances", "outbox", "execution_jobs",
                        "execution_plans", "execution_attempts", "lifecycle_audit_events", "legacy_execution_cutover",
                        "legacy_all_of_definitions", "legacy_plan_dispositions"))
                    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class), table);
                assertEquals(0L,
                        new com.flowops.persistence.JdbcExecutionRolloutQueries(jdbc).facts()
                            .counts()
                            .get("SCHEMA_NOT_CURRENT"));
                assertEquals(1,
                        jdbc.queryForObject("SELECT count(*) FROM execution_provider_contract_cutover", Integer.class));
                assertThrows(org.springframework.dao.DataAccessException.class,
                        () -> jdbc.execute("TRUNCATE lifecycle_audit_events"));
                assertThrows(org.springframework.dao.DataAccessException.class,
                        () -> jdbc.execute("TRUNCATE execution_attempt_events"));
                long lastRole = jdbc.queryForObject("SELECT max(id) FROM roles", Long.class);
                assertTrue(jdbc.queryForObject(
                        "INSERT INTO roles(code,name) VALUES ('BOOTSTRAP_VERIFY','Verify') RETURNING id",
                        Long.class) > lastRole);
            }
            finally {
                connection.setSchema(original);
                try (var statement = connection.createStatement()) {
                    statement.execute("DROP SCHEMA IF EXISTS " + fresh + " CASCADE");
                }
            }
        }
    }

}
