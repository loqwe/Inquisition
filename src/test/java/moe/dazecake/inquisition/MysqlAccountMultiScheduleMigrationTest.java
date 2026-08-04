package moe.dazecake.inquisition;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlAccountMultiScheduleMigrationTest {

    @Test
    void migrationCreatesNormalizedAccountTimesAndBackfillsLegacySchedules() throws Exception {
        var migration = read("/db/manual/mysql-account-multi-schedule-v2.sql");
        var table = tableDefinition(migration, "account_dispatch_time");

        assertTrue(table.contains("id BIGINT NOT NULL AUTO_INCREMENT"));
        assertTrue(table.contains("account_id BIGINT NOT NULL"));
        assertTrue(table.contains("schedule_time TIME NOT NULL"));
        assertTrue(table.contains("created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)"));
        assertTrue(table.contains("updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)"));
        assertTrue(table.contains("PRIMARY KEY (id)"));
        assertTrue(table.contains("UNIQUE KEY uk_account_dispatch_time (account_id, schedule_time)"));
        assertTrue(migration.contains("COLLATE=utf8mb4_0900_ai_ci"));
        assertTrue(migration.contains("INSERT IGNORE INTO account_dispatch_time"));
        assertTrue(migration.contains("FROM account_dispatch_config"));
        assertTrue(migration.contains("WHERE dispatch_mode = 'SCHEDULED'"));
        assertTrue(migration.contains("AND schedule_time IS NOT NULL"));
    }

    @Test
    void migrationAssertsStorageColumnsAndUniqueIndex() throws Exception {
        var migration = read("/db/manual/mysql-account-multi-schedule-v2.sql");

        assertTrue(migration.contains("CREATE PROCEDURE account_multi_schedule_assert_table"));
        assertTrue(migration.contains("CREATE PROCEDURE account_multi_schedule_assert_column"));
        assertTrue(migration.contains("CREATE PROCEDURE account_multi_schedule_assert_index"));
        assertTrue(migration.contains("SIGNAL SQLSTATE '45000'"));
        assertEquals(5, countOccurrences(migration,
                "CALL account_multi_schedule_assert_column("));
        assertEquals(2, countOccurrences(migration,
                "CALL account_multi_schedule_assert_index("));
        assertTrue(migration.contains("CALL account_multi_schedule_assert_index(" +
                "'account_dispatch_time', 'uk_account_dispatch_time', " +
                "'account_id,schedule_time', 0)"));
    }

    @Test
    void rollbackOnlyDropsTheV2TimeTable() throws Exception {
        var rollback = read("/db/manual/mysql-account-multi-schedule-v2-rollback.sql");

        assertTrue(rollback.contains("DROP TABLE IF EXISTS account_dispatch_time"));
        assertFalse(rollback.contains("DROP TABLE IF EXISTS account_dispatch_config"));
        assertFalse(rollback.contains("DROP TABLE IF EXISTS account_scheduled_run"));
        assertFalse(rollback.contains("ALTER TABLE"));
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
