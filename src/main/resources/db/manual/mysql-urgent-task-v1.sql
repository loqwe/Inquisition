CREATE TABLE IF NOT EXISTS urgent_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    game_day DATE NOT NULL,
    trigger_type VARCHAR(16) NOT NULL,
    task_mode VARCHAR(32) NOT NULL,
    priority INT NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6) NULL,
    last_error VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_urgent_task_account_game_day (account_id, game_day),
    KEY idx_urgent_task_dispatch (status, next_retry_at, priority, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DELIMITER $$
DROP PROCEDURE IF EXISTS urgent_task_add_column_if_missing$$
CREATE PROCEDURE urgent_task_add_column_if_missing(
    IN p_table_name VARCHAR(64),
    IN p_column_name VARCHAR(64),
    IN p_definition VARCHAR(255)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = p_table_name
          AND column_name = p_column_name
    ) THEN
        SET @urgent_task_sql = CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN `',
                                      p_column_name, '` ', p_definition);
        PREPARE urgent_task_stmt FROM @urgent_task_sql;
        EXECUTE urgent_task_stmt;
        DEALLOCATE PREPARE urgent_task_stmt;
    END IF;
END$$

CALL urgent_task_add_column_if_missing('task_assignment', 'task_mode',
                                       'VARCHAR(32) NOT NULL DEFAULT ''NORMAL'' AFTER `task_type`')$$
CALL urgent_task_add_column_if_missing('task_assignment', 'urgent_task_id',
                                       'BIGINT NULL AFTER `task_mode`')$$
CALL urgent_task_add_column_if_missing('task_assignment_history', 'task_mode',
                                       'VARCHAR(32) NOT NULL DEFAULT ''NORMAL'' AFTER `task_type`')$$
CALL urgent_task_add_column_if_missing('task_assignment_history', 'urgent_task_id',
                                       'BIGINT NULL AFTER `task_mode`')$$

DROP PROCEDURE urgent_task_add_column_if_missing$$
DELIMITER ;
