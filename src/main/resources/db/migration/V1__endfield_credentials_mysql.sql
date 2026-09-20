SET @schema_name = DATABASE();

SET @migration_sql = IF(
    EXISTS(
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = @schema_name
          AND TABLE_NAME = 'account'
          AND COLUMN_NAME = 'password_ciphertext'
    ),
    'SELECT 1',
    'ALTER TABLE account ADD COLUMN password_ciphertext TEXT NULL AFTER password'
);
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF(
    EXISTS(
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = @schema_name
          AND TABLE_NAME = 'account'
          AND COLUMN_NAME = 'password_verifier'
    ),
    'SELECT 1',
    'ALTER TABLE account ADD COLUMN password_verifier VARCHAR(100) NULL AFTER password_ciphertext'
);
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
