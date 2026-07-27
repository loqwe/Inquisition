DELIMITER $$
DROP PROCEDURE IF EXISTS urgent_task_drop_column_if_exists$$
CREATE PROCEDURE urgent_task_drop_column_if_exists(
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
        SET @urgent_task_sql = CONCAT('ALTER TABLE `', p_table_name,
                                      '` DROP COLUMN `', p_column_name, '`');
        PREPARE urgent_task_stmt FROM @urgent_task_sql;
        EXECUTE urgent_task_stmt;
        DEALLOCATE PREPARE urgent_task_stmt;
    END IF;
END$$

CALL urgent_task_drop_column_if_exists('task_assignment_history', 'urgent_task_id')$$
CALL urgent_task_drop_column_if_exists('task_assignment_history', 'task_mode')$$
CALL urgent_task_drop_column_if_exists('task_assignment', 'urgent_task_id')$$
CALL urgent_task_drop_column_if_exists('task_assignment', 'task_mode')$$

DROP PROCEDURE urgent_task_drop_column_if_exists$$
DELIMITER ;

DROP TABLE IF EXISTS urgent_task;
