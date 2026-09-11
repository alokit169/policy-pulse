package com.policypulse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the entity/migration contract. The application runs Flyway and then
 * Hibernate with ddl-auto=validate, so an entity that drifts from the
 * migrations fails startup. Booting the context here makes that drift fail the
 * build instead of a deployment.
 */
class SchemaIntegrityTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void contextStartsSoFlywayAppliedAndHibernateValidated() {
        // Reaching this point means both ran successfully against real Postgres.
        assertThat(jdbc).isNotNull();
    }

    @Test
    void allMigrationsAppliedSuccessfully() {
        List<Boolean> results = jdbc.queryForList(
                "SELECT success FROM flyway_schema_history ORDER BY installed_rank", Boolean.class);

        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(Boolean::booleanValue);
    }

    @Test
    void coreTablesExist() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);

        assertThat(tables).contains(
                "organizations", "users", "customers", "policies", "premium_payments",
                "reminders", "conversations", "conversation_messages", "follow_ups",
                "human_tasks", "in_app_notifications", "audit_logs", "reminder_configurations");
    }
}
