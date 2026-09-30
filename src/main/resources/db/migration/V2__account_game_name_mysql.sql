SET @schema_name = DATABASE();

SET @migration_sql = IF(
    EXISTS(
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = @schema_name
          AND TABLE_NAME = 'account'
          AND COLUMN_NAME = 'game_name'
    ),
    'SELECT 1',
    'ALTER TABLE account ADD COLUMN game_name VARCHAR(128) NULL AFTER name'
);
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
