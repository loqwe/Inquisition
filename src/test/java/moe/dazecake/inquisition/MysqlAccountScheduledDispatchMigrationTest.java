package moe.dazecake.inquisition;

import moe.dazecake.inquisition.model.entity.AccountDispatchConfigEntity;
import moe.dazecake.inquisition.model.entity.AccountScheduledRunEntity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlAccountScheduledDispatchMigrationTest {

    @Test
    void migrationCreatesIndependentAccountDispatchConfiguration() throws Exception {
        var migration = read("/db/manual/mysql-account-scheduled-dispatch-v1.sql");
        var table = tableDefinition(migration, "account_dispatch_config");

        assertTrue(table.contains("account_id BIGINT NOT NULL"));
        assertTrue(table.contains("dispatch_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO'"));
        assertTrue(table.contains("schedule_time TIME NULL"));
        assertTrue(table.contains("next_scheduled_at DATETIME(6) NULL"));
        assertTrue(table.contains("activation_pending TINYINT NOT NULL DEFAULT 0"));
        assertTrue(table.contains("created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)"));
        assertTrue(table.contains("updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)"));
        assertTrue(table.contains("PRIMARY KEY (account_id)"));
        assertTrue(table.contains("KEY idx_account_dispatch_config_due (dispatch_mode, next_scheduled_at)"));
    }

    @Test
    void migrationCreatesScheduledRunsWithIdempotencyAndDispatchIndexes() throws Exception {
        var migration = read("/db/manual/mysql-account-scheduled-dispatch-v1.sql");
        var table = tableDefinition(migration, "account_scheduled_run");

        assertTrue(table.contains("id BIGINT NOT NULL AUTO_INCREMENT"));
        assertTrue(table.contains("account_id BIGINT NOT NULL"));
        assertTrue(table.contains("scheduled_for DATETIME(6) NOT NULL"));
        assertTrue(table.contains("game_day DATE NOT NULL"));
        assertTrue(table.contains("status VARCHAR(24) NOT NULL"));
        assertTrue(table.contains("attempt_count INT NOT NULL DEFAULT 0"));
        assertTrue(table.contains("next_retry_at DATETIME(6) NULL"));
        assertTrue(table.contains("last_error VARCHAR(255) NULL"));
        assertTrue(table.contains("created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)"));
        assertTrue(table.contains("updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)"));
        assertTrue(table.contains("finished_at DATETIME(6) NULL"));
        assertTrue(table.contains("UNIQUE KEY uk_account_scheduled_run_slot (account_id, scheduled_for)"));
        assertTrue(table.contains("KEY idx_account_scheduled_run_dispatch (status, next_retry_at, scheduled_for)"));
    }

    @Test
    void entitiesInitializeRequiredAuditTimestamps() {
        var config = new AccountDispatchConfigEntity();
        var run = new AccountScheduledRunEntity();

        assertNotNull(config.getCreatedAt());
        assertNotNull(config.getUpdatedAt());
        assertNotNull(run.getCreatedAt());
        assertNotNull(run.getUpdatedAt());
    }

    @Test
    void migrationAddsLeaseColumnsWithOneInstantAlterPerTable() throws Exception {
        var migration = read("/db/manual/mysql-account-scheduled-dispatch-v1.sql");

        assertTrue(migration.contains("CREATE PROCEDURE account_scheduled_dispatch_add_columns_if_missing"));
        assertEquals(2, countOccurrences(migration,
                "CALL account_scheduled_dispatch_add_columns_if_missing("));
        assertEquals(1, countOccurrences(migration, "'ALTER TABLE `"));
        assertTrue(migration.contains("ADD COLUMN `dispatch_source` VARCHAR(24) NOT NULL DEFAULT ''AUTO''"));
        assertTrue(migration.contains("ADD COLUMN `scheduled_run_id` BIGINT NULL"));
        assertTrue(migration.contains("ALGORITHM=INSTANT"));
    }

    @Test
    void migrationValidatesSchemaAndSignalsOnDrift() throws Exception {
        var migration = read("/db/manual/mysql-account-scheduled-dispatch-v1.sql");

        assertTrue(migration.contains("CREATE PROCEDURE account_scheduled_dispatch_assert_column"));
        assertTrue(migration.contains("CREATE PROCEDURE account_scheduled_dispatch_assert_index"));
        assertTrue(migration.contains("information_schema.columns"));
        assertTrue(migration.contains("information_schema.statistics"));
        assertTrue(migration.contains("SIGNAL SQLSTATE '45000'"));
        assertEquals(22, countOccurrences(migration,
                "CALL account_scheduled_dispatch_assert_column("));
        assertEquals(5, countOccurrences(migration,
                "CALL account_scheduled_dispatch_assert_index("));
        assertTrue(migration.contains("CALL account_scheduled_dispatch_assert_column(" +
                "'account_dispatch_config', 'updated_at', 'datetime(6)', 'NO', " +
                "'CURRENT_TIMESTAMP(6)', 0, 'on update current_timestamp(6)')"));
        assertTrue(migration.contains("CALL account_scheduled_dispatch_assert_column(" +
                "'task_assignment_history', 'dispatch_source', 'varchar(24)', 'NO', 'AUTO', 0, NULL)"));
        assertTrue(migration.contains("CALL account_scheduled_dispatch_assert_index(" +
                "'account_scheduled_run', 'uk_account_scheduled_run_slot', " +
                "'account_id,scheduled_for', 0)"));
        assertTrue(migration.contains("CALL account_scheduled_dispatch_assert_index(" +
                "'account_scheduled_run', 'idx_account_scheduled_run_dispatch', " +
                "'status,next_retry_at,scheduled_for', 1)"));
        assertTrue(migration.indexOf("CALL account_scheduled_dispatch_add_columns_if_missing(" +
                "'task_assignment_history')") < migration.indexOf(
                "CALL account_scheduled_dispatch_assert_column("));
    }

    @Test
    void rollbackOnlyRemovesScheduledDispatchArtifacts() throws Exception {
        var rollback = read("/db/manual/mysql-account-scheduled-dispatch-v1-rollback.sql");

        assertTrue(rollback.contains("DROP TABLE IF EXISTS account_scheduled_run"));
        assertTrue(rollback.contains("DROP TABLE IF EXISTS account_dispatch_config"));
        assertTrue(rollback.contains("CREATE PROCEDURE account_scheduled_dispatch_drop_columns_if_present"));
        assertEquals(2, countOccurrences(rollback,
                "CALL account_scheduled_dispatch_drop_columns_if_present("));
        assertEquals(1, countOccurrences(rollback, "'ALTER TABLE `"));
        assertTrue(rollback.contains("DROP COLUMN `scheduled_run_id`"));
        assertTrue(rollback.contains("DROP COLUMN `dispatch_source`"));
        assertTrue(rollback.contains("ALGORITHM=INSTANT"));
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

    private String tableDefinition(String migration, String tableName) {
        var marker = "CREATE TABLE IF NOT EXISTS " + tableName;
        var start = migration.indexOf(marker);
        assertTrue(start >= 0, "table definition must exist: " + tableName);
        var end = migration.indexOf(';', start);
        assertTrue(end > start, "table definition must terminate: " + tableName);
        return migration.substring(start, end);
    }

    private int countOccurrences(String text, String needle) {
        var count = 0;
        var index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
