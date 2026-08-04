DELIMITER $$
DROP PROCEDURE IF EXISTS reliability_drop_index_if_exists$$
CREATE PROCEDURE reliability_drop_index_if_exists(
    IN p_table_name VARCHAR(64),
    IN p_index_name VARCHAR(64)
)
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = p_table_name
          AND index_name = p_index_name
    ) THEN
        SET @reliability_sql = CONCAT('ALTER TABLE `', p_table_name, '` DROP KEY `', p_index_name, '`');
        PREPARE reliability_stmt FROM @reliability_sql;
        EXECUTE reliability_stmt;
        DEALLOCATE PREPARE reliability_stmt;
    END IF;
END$$

CALL reliability_drop_index_if_exists('log', 'idx_log_assignment_id')$$
CALL reliability_drop_index_if_exists('log', 'idx_log_account_id_time')$$
CALL reliability_drop_index_if_exists('log', 'idx_log_account_time')$$
DROP PROCEDURE reliability_drop_index_if_exists$$

DROP PROCEDURE IF EXISTS reliability_drop_column_if_exists$$
CREATE PROCEDURE reliability_drop_column_if_exists(
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
        SET @reliability_sql = CONCAT(
            'ALTER TABLE `', p_table_name,
            '` DROP COLUMN `', p_column_name, '`'
        );
        PREPARE reliability_stmt FROM @reliability_sql;
        EXECUTE reliability_stmt;
        DEALLOCATE PREPARE reliability_stmt;
    END IF;
END$$

CALL reliability_drop_column_if_exists('log', 'assignment_id')$$
CALL reliability_drop_column_if_exists('log', 'account_id')$$
DROP PROCEDURE reliability_drop_column_if_exists$$
DELIMITER ;

DROP TABLE IF EXISTS skland_credential;
DROP TABLE IF EXISTS device_runtime;
DROP TABLE IF EXISTS account_runtime;
DROP TABLE IF EXISTS task_assignment_history;
DROP TABLE IF EXISTS task_assignment;
