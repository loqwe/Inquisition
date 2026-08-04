package moe.dazecake.inquisition;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlUrgentTaskMigrationTest {

    @Test
    void migrationPersistsUrgencyAndInternalAssignmentMode() throws Exception {
        var migration = read("/db/manual/mysql-urgent-task-v1.sql");

        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS urgent_task"));
        assertTrue(migration.contains("UNIQUE KEY uk_urgent_task_account_game_day (account_id, game_day)"));
        assertTrue(migration.contains("KEY idx_urgent_task_dispatch (status, next_retry_at, priority, created_at)"));
        assertTrue(migration.contains("CALL urgent_task_add_column_if_missing('task_assignment', 'task_mode'"));
        assertTrue(migration.contains("CALL urgent_task_add_column_if_missing('task_assignment', 'urgent_task_id'"));
        assertTrue(migration.contains("CALL urgent_task_add_column_if_missing('task_assignment_history', 'task_mode'"));
        assertTrue(migration.contains("CALL urgent_task_add_column_if_missing('task_assignment_history', 'urgent_task_id'"));
    }

    @Test
    void rollbackOnlyRemovesUrgentTaskArtifacts() throws Exception {
        var rollback = read("/db/manual/mysql-urgent-task-v1-rollback.sql");

        assertTrue(rollback.contains("DROP TABLE IF EXISTS urgent_task"));
        assertTrue(rollback.contains("urgent_task_id"));
        assertTrue(rollback.contains("task_mode"));
        assertFalse(rollback.contains("DROP TABLE IF EXISTS task_assignment;"));
        assertFalse(rollback.contains("DROP TABLE IF EXISTS account_runtime"));
    }

    private String read(String path) throws Exception {
        try (var stream = getClass().getResourceAsStream(path)) {
            assertNotNull(stream, "migration resource must exist: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
