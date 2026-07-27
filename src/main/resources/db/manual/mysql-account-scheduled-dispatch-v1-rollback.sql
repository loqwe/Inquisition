DELIMITER $$
DROP PROCEDURE IF EXISTS account_scheduled_dispatch_drop_column_if_exists$$
CREATE PROCEDURE account_scheduled_dispatch_drop_column_if_exists(
    IN p_table_name VARCHAR(64),
    IN p_column_name VARCHAR(64)
)
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = p_table_name
          AND column_name = p_column_name
    ) THEN
        SET @account_scheduled_dispatch_sql = CONCAT(
            'ALTER TABLE `', p_table_name,
            '` DROP COLUMN `', p_column_name, '`'
        );
        PREPARE account_scheduled_dispatch_stmt FROM @account_scheduled_dispatch_sql;
        EXECUTE account_scheduled_dispatch_stmt;
        DEALLOCATE PREPARE account_scheduled_dispatch_stmt;
    END IF;
END$$

CALL account_scheduled_dispatch_drop_column_if_exists('task_assignment_history', 'scheduled_run_id')$$
CALL account_scheduled_dispatch_drop_column_if_exists('task_assignment_history', 'dispatch_source')$$
CALL account_scheduled_dispatch_drop_column_if_exists('task_assignment', 'scheduled_run_id')$$
CALL account_scheduled_dispatch_drop_column_if_exists('task_assignment', 'dispatch_source')$$

DROP PROCEDURE account_scheduled_dispatch_drop_column_if_exists$$
DELIMITER ;

DROP TABLE IF EXISTS account_scheduled_run;
DROP TABLE IF EXISTS account_dispatch_config;
