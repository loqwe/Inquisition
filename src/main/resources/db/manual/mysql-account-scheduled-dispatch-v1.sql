CREATE TABLE IF NOT EXISTS account_dispatch_config (
    account_id BIGINT NOT NULL,
    dispatch_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO',
    schedule_time TIME NULL,
    next_scheduled_at DATETIME(6) NULL,
    activation_pending TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (account_id),
    KEY idx_account_dispatch_config_due (dispatch_mode, next_scheduled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS account_scheduled_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    scheduled_for DATETIME(6) NOT NULL,
    game_day DATE NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6) NULL,
    last_error VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_scheduled_run_slot (account_id, scheduled_for),
    KEY idx_account_scheduled_run_dispatch (status, next_retry_at, scheduled_for)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DELIMITER $$
DROP PROCEDURE IF EXISTS account_scheduled_dispatch_add_column_if_missing$$
CREATE PROCEDURE account_scheduled_dispatch_add_column_if_missing(
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
        SET @account_scheduled_dispatch_sql = CONCAT(
            'ALTER TABLE `', p_table_name, '` ADD COLUMN `',
            p_column_name, '` ', p_definition
        );
        PREPARE account_scheduled_dispatch_stmt FROM @account_scheduled_dispatch_sql;
        EXECUTE account_scheduled_dispatch_stmt;
        DEALLOCATE PREPARE account_scheduled_dispatch_stmt;
    END IF;
END$$

CALL account_scheduled_dispatch_add_column_if_missing('task_assignment', 'dispatch_source',
    'VARCHAR(24) NOT NULL DEFAULT ''AUTO'' AFTER `urgent_task_id`'
)$$
CALL account_scheduled_dispatch_add_column_if_missing('task_assignment', 'scheduled_run_id',
    'BIGINT NULL AFTER `dispatch_source`'
)$$
CALL account_scheduled_dispatch_add_column_if_missing('task_assignment_history', 'dispatch_source',
    'VARCHAR(24) NOT NULL DEFAULT ''AUTO'' AFTER `urgent_task_id`'
)$$
CALL account_scheduled_dispatch_add_column_if_missing('task_assignment_history', 'scheduled_run_id',
    'BIGINT NULL AFTER `dispatch_source`'
)$$

DROP PROCEDURE account_scheduled_dispatch_add_column_if_missing$$
DELIMITER ;
