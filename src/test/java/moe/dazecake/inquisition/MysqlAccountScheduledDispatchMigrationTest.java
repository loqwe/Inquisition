package moe.dazecake.inquisition;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlAccountScheduledDispatchMigrationTest {

    @Test
    void migrationCreatesIndependentAccountDispatchConfiguration() throws Exception {
        var migration = read("/db/manual/mysql-account-scheduled-dispatch-v1.sql");

        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS account_dispatch_config"));
        assertTrue(migration.contains("account_id BIGINT NOT NULL"));
        assertTrue(migration.contains("dispatch_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO'"));
        assertTrue(migration.contains("schedule_time TIME NULL"));
        assertTrue(migration.contains("next_scheduled_at DATETIME(6) NULL"));
        assertTrue(migration.contains("activation_pending TINYINT NOT NULL DEFAULT 0"));
        assertTrue(migration.contains("created_at DATETIME(6) NOT NULL"));
        assertTrue(migration.contains("updated_at DATETIME(6) NOT NULL"));
        assertTrue(migration.contains("PRIMARY KEY (account_id)"));
        assertTrue(migration.contains("KEY idx_account_dispatch_config_due (dispatch_mode, next_scheduled_at)"));
    }

    @Test
    void migrationCreatesScheduledRunsWithIdempotencyAndDispatchIndexes() throws Exception {
        var migration = read("/db/manual/mysql-account-scheduled-dispatch-v1.sql");

        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS account_scheduled_run"));
        assertTrue(migration.contains("id BIGINT NOT NULL AUTO_INCREMENT"));
        assertTrue(migration.contains("account_id BIGINT NOT NULL"));
        assertTrue(migration.contains("scheduled_for DATETIME(6) NOT NULL"));
        assertTrue(migration.contains("game_day DATE NOT NULL"));
        assertTrue(migration.contains("status VARCHAR(24) NOT NULL"));
        assertTrue(migration.contains("attempt_count INT NOT NULL DEFAULT 0"));
        assertTrue(migration.contains("next_retry_at DATETIME(6) NULL"));
        assertTrue(migration.contains("last_error VARCHAR(255) NULL"));
        assertTrue(migration.contains("finished_at DATETIME(6) NULL"));
        assertTrue(migration.contains("UNIQUE KEY uk_account_scheduled_run_slot (account_id, scheduled_for)"));
        assertTrue(migration.contains("KEY idx_account_scheduled_run_dispatch (status, next_retry_at, scheduled_for)"));
    }

    @Test
    void migrationAddsDispatchOriginToActiveAndHistoricalAssignments() throws Exception {
        var migration = read("/db/manual/mysql-account-scheduled-dispatch-v1.sql");

        assertTrue(migration.contains("CREATE PROCEDURE account_scheduled_dispatch_add_column_if_missing"));
        assertTrue(migration.contains("information_schema.columns"));
        assertTrue(migration.contains("CALL account_scheduled_dispatch_add_column_if_missing('task_assignment', 'dispatch_source'"));
        assertTrue(migration.contains("CALL account_scheduled_dispatch_add_column_if_missing('task_assignment', 'scheduled_run_id'"));
        assertTrue(migration.contains("CALL account_scheduled_dispatch_add_column_if_missing('task_assignment_history', 'dispatch_source'"));
        assertTrue(migration.contains("CALL account_scheduled_dispatch_add_column_if_missing('task_assignment_history', 'scheduled_run_id'"));
        assertTrue(migration.contains("VARCHAR(24) NOT NULL DEFAULT ''AUTO''"));
        assertTrue(migration.contains("BIGINT NULL"));
    }

    @Test
    void rollbackOnlyRemovesScheduledDispatchArtifacts() throws Exception {
        var rollback = read("/db/manual/mysql-account-scheduled-dispatch-v1-rollback.sql");

        assertTrue(rollback.contains("DROP TABLE IF EXISTS account_scheduled_run"));
        assertTrue(rollback.contains("DROP TABLE IF EXISTS account_dispatch_config"));
        assertTrue(rollback.contains("CALL account_scheduled_dispatch_drop_column_if_exists('task_assignment_history', 'scheduled_run_id')"));
        assertTrue(rollback.contains("CALL account_scheduled_dispatch_drop_column_if_exists('task_assignment_history', 'dispatch_source')"));
        assertTrue(rollback.contains("CALL account_scheduled_dispatch_drop_column_if_exists('task_assignment', 'scheduled_run_id')"));
        assertTrue(rollback.contains("CALL account_scheduled_dispatch_drop_column_if_exists('task_assignment', 'dispatch_source')"));
        assertFalse(rollback.contains("DROP TABLE IF EXISTS task_assignment;"));
        assertFalse(rollback.contains("DROP TABLE IF EXISTS task_assignment_history;"));
        assertFalse(rollback.contains("urgent_task"));
        assertFalse(rollback.contains("task_mode"));
        assertFalse(rollback.contains("urgent_task_id"));
        assertFalse(rollback.contains("account_runtime"));
        assertFalse(rollback.contains("device_runtime"));
        assertFalse(rollback.contains("scheduled_task_runtime"));
    }

    private String read(String path) throws Exception {
        try (var stream = getClass().getResourceAsStream(path)) {
            assertNotNull(stream, "migration resource must exist: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
