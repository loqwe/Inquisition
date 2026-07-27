CREATE TABLE IF NOT EXISTS task_assignment (
    assignment_id VARCHAR(36) NOT NULL,
    account_id BIGINT NOT NULL,
    device_token VARCHAR(255) NOT NULL,
    task_type VARCHAR(32) NULL,
    assigned_at DATETIME(6) NOT NULL,
    lease_expires_at DATETIME(6) NOT NULL,
    last_progress_at DATETIME(6) NULL,
    game_started TINYINT NOT NULL DEFAULT 0,
    last_progress_title VARCHAR(255) NULL,
    last_progress_detail VARCHAR(255) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    long_task_notified TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (assignment_id),
    UNIQUE KEY uk_task_assignment_account (account_id),
    UNIQUE KEY uk_task_assignment_device (device_token),
    KEY idx_task_assignment_expiry (lease_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS task_assignment_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    assignment_id VARCHAR(36) NOT NULL,
    account_id BIGINT NOT NULL,
    device_token VARCHAR(255) NOT NULL,
    task_type VARCHAR(32) NULL,
    status VARCHAR(32) NOT NULL,
    assigned_at DATETIME(6) NOT NULL,
    lease_expires_at DATETIME(6) NOT NULL,
    last_progress_at DATETIME(6) NULL,
    game_started TINYINT NOT NULL DEFAULT 0,
    last_progress_title VARCHAR(255) NULL,
    last_progress_detail VARCHAR(255) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    long_task_notified TINYINT NOT NULL DEFAULT 0,
    reason VARCHAR(255) NULL,
    finished_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_assignment_history_assignment (assignment_id),
    KEY idx_task_assignment_history_account_time (account_id, finished_at),
    KEY idx_task_assignment_history_device_time (device_token, finished_at),
    KEY idx_task_assignment_history_status_time (status, finished_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS account_runtime (
    account_id BIGINT NOT NULL,
    last_valid_log_at DATETIME(6) NULL,
    last_login_at DATETIME(6) NULL,
    last_task_completed_at DATETIME(6) NULL,
    last_skland_query_at DATETIME(6) NULL,
    last_online_at DATETIME(6) NULL,
    sanity INT NULL,
    max_sanity INT NULL,
    sanity_observed_at DATETIME(6) NULL,
    sanity_source VARCHAR(32) NULL,
    next_eligible_at DATETIME(6) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    last_failure_at DATETIME(6) NULL,
    last_failure_device_token VARCHAR(255) NULL,
    game_day_key DATE NULL,
    missing_log_notified TINYINT NOT NULL DEFAULT 0,
    abnormal TINYINT NOT NULL DEFAULT 0,
    last_error VARCHAR(255) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (account_id),
    KEY idx_account_runtime_missing_log (last_valid_log_at, last_skland_query_at),
    KEY idx_account_runtime_next_eligible (next_eligible_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS device_runtime (
    device_token VARCHAR(255) NOT NULL,
    state VARCHAR(16) NOT NULL,
    last_heartbeat_at DATETIME(6) NULL,
    offline_since DATETIME(6) NULL,
    last_notice_level INT NOT NULL DEFAULT 0,
    last_notice_at DATETIME(6) NULL,
    recovery_pending TINYINT NOT NULL DEFAULT 0,
    consecutive_failures INT NOT NULL DEFAULT 0,
    last_failure_notice_count INT NOT NULL DEFAULT 0,
    last_failure_notice_at DATETIME(6) NULL,
    suspended_until DATETIME(6) NULL,
    client_version VARCHAR(64) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (device_token),
    KEY idx_device_runtime_state_notice (state, recovery_pending, offline_since),
    KEY idx_device_runtime_suspension (suspended_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS skland_credential (
    account_id BIGINT NOT NULL,
    access_token VARCHAR(255) NULL,
    cred VARCHAR(255) NULL,
    cred_token VARCHAR(255) NULL,
    uid VARCHAR(64) NULL,
    channel_master_id VARCHAR(64) NULL,
    last_refresh_at DATETIME(6) NULL,
    last_error VARCHAR(255) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DELIMITER $$
DROP PROCEDURE IF EXISTS reliability_add_column_if_missing$$
CREATE PROCEDURE reliability_add_column_if_missing(
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
        SET @reliability_sql = CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN `',
                                      p_column_name, '` ', p_definition);
        PREPARE reliability_stmt FROM @reliability_sql;
        EXECUTE reliability_stmt;
        DEALLOCATE PREPARE reliability_stmt;
    END IF;
END$$

DROP PROCEDURE IF EXISTS reliability_add_index_if_missing$$
CREATE PROCEDURE reliability_add_index_if_missing(
    IN p_table_name VARCHAR(64),
    IN p_index_name VARCHAR(64),
    IN p_definition VARCHAR(255)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = p_table_name
          AND index_name = p_index_name
    ) THEN
        SET @reliability_sql = CONCAT('ALTER TABLE `', p_table_name, '` ADD KEY `',
                                      p_index_name, '` ', p_definition,
                                      ', ALGORITHM=INPLACE, LOCK=NONE');
        PREPARE reliability_stmt FROM @reliability_sql;
        EXECUTE reliability_stmt;
        DEALLOCATE PREPARE reliability_stmt;
    END IF;
END$$

CALL reliability_add_column_if_missing('log', 'account_id', 'BIGINT NULL')$$
CALL reliability_add_column_if_missing('log', 'assignment_id', 'VARCHAR(36) NULL')$$
CALL reliability_add_column_if_missing('task_assignment', 'long_task_notified', 'TINYINT NOT NULL DEFAULT 0')$$
CALL reliability_add_column_if_missing('task_assignment_history', 'long_task_notified', 'TINYINT NOT NULL DEFAULT 0')$$
CALL reliability_add_column_if_missing('device_runtime', 'last_failure_notice_count', 'INT NOT NULL DEFAULT 0')$$
CALL reliability_add_column_if_missing('device_runtime', 'last_failure_notice_at', 'DATETIME(6) NULL')$$

CALL reliability_add_index_if_missing('log', 'idx_log_account_time', '(`account`, `time`)')$$
CALL reliability_add_index_if_missing('log', 'idx_log_account_id_time', '(`account_id`, `time`)')$$
CALL reliability_add_index_if_missing('log', 'idx_log_assignment_id', '(`assignment_id`)')$$

DROP PROCEDURE reliability_add_column_if_missing$$
DROP PROCEDURE reliability_add_index_if_missing$$
DELIMITER ;
