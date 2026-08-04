-- Device groups: explicit IMPORTANT/BACKUP role with legacy name compatibility.
SET @device_role_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'device'
      AND column_name = 'device_role'
);
SET @device_role_added = IF(@device_role_exists = 0, 1, 0);
SET @device_role_sql = IF(
    @device_role_exists = 0,
    'ALTER TABLE `device` ADD COLUMN `device_role` VARCHAR(16) NOT NULL DEFAULT ''BACKUP''',
    'SELECT 1'
);
PREPARE device_role_stmt FROM @device_role_sql;
EXECUTE device_role_stmt;
DEALLOCATE PREPARE device_role_stmt;

UPDATE `device`
SET `device_role` = CASE
    WHEN UPPER(TRIM(`device_name`)) IN ('A', '1', '2') THEN 'IMPORTANT'
    ELSE 'BACKUP'
END
WHERE @device_role_added = 1
   OR `device_role` IS NULL
   OR TRIM(`device_role`) = ''
   OR UPPER(TRIM(`device_role`)) NOT IN ('IMPORTANT', 'BACKUP');
