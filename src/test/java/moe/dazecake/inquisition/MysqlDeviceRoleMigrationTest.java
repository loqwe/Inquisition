package moe.dazecake.inquisition;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlDeviceRoleMigrationTest {

    @Test
    void migrationAddsRoleAndBackfillsLegacyDeviceNames() throws Exception {
        var migration = read("/db/manual/mysql-device-role-v1.sql");

        assertTrue(migration.contains("ADD COLUMN `device_role` VARCHAR(16) NOT NULL DEFAULT ''BACKUP''"));
        assertTrue(migration.contains("WHEN UPPER(TRIM(`device_name`)) IN ('A', '1', '2') THEN 'IMPORTANT'"));
        assertTrue(migration.contains("ELSE 'BACKUP'"));
        assertTrue(migration.contains("UPPER(TRIM(`device_role`)) NOT IN ('IMPORTANT', 'BACKUP')"));
    }

    @Test
    void migrationIsIdempotentAndRollbackOnlyDropsRoleColumn() throws Exception {
        var migration = read("/db/manual/mysql-device-role-v1.sql");
        var rollback = read("/db/manual/mysql-device-role-v1-rollback.sql");

        assertTrue(migration.contains("SET @device_role_exists"));
        assertTrue(migration.contains("PREPARE device_role_stmt FROM @device_role_sql"));
        assertTrue(rollback.contains("ALTER TABLE `device` DROP COLUMN `device_role`"));
    }

    private String read(String path) throws Exception {
        try (var stream = getClass().getResourceAsStream(path)) {
            assertNotNull(stream, "migration resource must exist: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
