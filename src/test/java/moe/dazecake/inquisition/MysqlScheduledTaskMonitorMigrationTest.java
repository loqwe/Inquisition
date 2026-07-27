package moe.dazecake.inquisition;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlScheduledTaskMonitorMigrationTest {

    @Test
    void migrationCreatesOnlyTheBoundedRuntimeStateTable() throws Exception {
        var migration = read("/db/manual/mysql-scheduled-task-monitor-v1.sql");

        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS scheduled_task_runtime"));
        assertTrue(migration.contains("last_error VARCHAR(1000)"));
        assertTrue(migration.contains("PRIMARY KEY (task_key)"));
        assertFalse(migration.contains("scheduled_task_history"));
    }

    @Test
    void rollbackDropsOnlyTheScheduledTaskRuntimeTable() throws Exception {
        var rollback = read("/db/manual/mysql-scheduled-task-monitor-v1-rollback.sql");

        assertTrue(rollback.contains("DROP TABLE IF EXISTS scheduled_task_runtime"));
        assertFalse(rollback.contains("task_assignment"));
        assertFalse(rollback.contains("device_runtime"));
        assertFalse(rollback.contains("account_runtime"));
    }

    private String read(String path) throws Exception {
        try (var stream = getClass().getResourceAsStream(path)) {
            assertNotNull(stream, "migration resource must exist: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
