package moe.dazecake.inquisition;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlSchemaCompatibilityTest {

    private static final Set<String> TABLES = Set.of(
            "task_definition", "task_run", "run_log", "run_image", "audit_event");

    @Test
    void sqliteSchemaCreatesControlTables() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            apply(connection, "db/schema-sqlite.sql");
            apply(connection, "db/schema-sqlite.sql");
            assertControlSchema(connection);
        }
    }

    @Test
    void mysqlSchemaIsExecutableInMysqlCompatibilityMode() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:control;MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            apply(connection, "db/schema-mysql.sql");
            apply(connection, "db/schema-mysql.sql");
            assertControlSchema(connection);
        }
    }

    private static void apply(Connection connection, String resource) throws SQLException {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource(resource));
        populator.setSeparator(";");
        populator.populate(connection);
    }

    private static void assertControlSchema(Connection connection) throws SQLException {
        for (String table : TABLES) {
            Set<String> columns = columns(connection, table);
            assertFalse(columns.isEmpty(), () -> "missing table " + table);
            assertFalse(columns.contains("password"), () -> table + " exposes a password column");
            assertFalse(columns.contains("device_token"), () -> table + " exposes a device token column");
        }

        assertColumns(connection, "task_definition",
                "account_id", "task_config", "schedule_type", "version", "deleted");
        assertColumns(connection, "task_run",
                "account_id", "device_id", "run_key", "attempt_id", "lease_id", "status");
        assertColumns(connection, "run_log",
                "task_run_id", "account_id", "sequence_no", "level", "message");
        assertColumns(connection, "run_image",
                "task_run_id", "account_id", "storage_key", "content_type", "sha256");
        assertColumns(connection, "audit_event",
                "actor_type", "actor_id", "event_type", "resource_type", "created_at");

        assertIndex(connection, "task_definition", "idx_task_definition_account");
        assertIndex(connection, "task_run", "idx_task_run_dispatch");
        assertIndex(connection, "run_log", "idx_run_log_account_time");
        assertIndex(connection, "run_image", "idx_run_image_account_time");
        assertIndex(connection, "audit_event", "idx_audit_actor_time");
    }

    private static void assertColumns(Connection connection, String table, String... expected)
            throws SQLException {
        Set<String> columns = columns(connection, table);
        for (String column : expected) {
            assertTrue(columns.contains(column), () -> table + " missing column " + column);
        }
    }

    private static Set<String> columns(Connection connection, String table) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (ResultSet result = connection.getMetaData().getColumns(
                connection.getCatalog(), null, table, null)) {
            while (result.next()) {
                columns.add(result.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return columns;
    }

    private static void assertIndex(Connection connection, String table, String expected)
            throws SQLException {
        Set<String> indexes = new HashSet<>();
        try (ResultSet result = connection.getMetaData().getIndexInfo(
                connection.getCatalog(), null, table, false, false)) {
            while (result.next()) {
                String name = result.getString("INDEX_NAME");
                if (name != null) {
                    indexes.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
        assertTrue(indexes.contains(expected), () -> table + " missing index " + expected);
    }
}
