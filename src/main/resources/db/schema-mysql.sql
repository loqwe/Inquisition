CREATE TABLE IF NOT EXISTS task_definition (
    id BIGINT NOT NULL AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    task_config LONGTEXT NOT NULL,
    schedule_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    schedule_expression VARCHAR(255),
    sort_order INTEGER NOT NULL DEFAULT 0,
    version INTEGER NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_task_definition_account (account_id, deleted, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS task_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_definition_id BIGINT,
    account_id BIGINT NOT NULL,
    device_id BIGINT,
    run_key VARCHAR(64) NOT NULL,
    attempt_id VARCHAR(64) NOT NULL,
    lease_id VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    trigger_type VARCHAR(32) NOT NULL,
    config_snapshot LONGTEXT NOT NULL,
    lease_expires_at DATETIME(3),
    started_at DATETIME(3),
    finished_at DATETIME(3),
    error_code VARCHAR(64),
    error_message TEXT,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_task_run_key (run_key),
    UNIQUE KEY uq_task_run_attempt (attempt_id),
    KEY idx_task_run_dispatch (status, lease_expires_at, created_at),
    KEY idx_task_run_account_time (account_id, created_at),
    KEY idx_task_run_device_time (device_id, created_at),
    CONSTRAINT fk_task_run_definition FOREIGN KEY (task_definition_id) REFERENCES task_definition (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS run_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_run_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    sequence_no INTEGER NOT NULL,
    level VARCHAR(16) NOT NULL,
    phase VARCHAR(64),
    message TEXT NOT NULL,
    context_json LONGTEXT,
    occurred_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_run_log_sequence (task_run_id, sequence_no),
    KEY idx_run_log_account_time (account_id, occurred_at),
    KEY idx_run_log_run_time (task_run_id, occurred_at),
    CONSTRAINT fk_run_log_run FOREIGN KEY (task_run_id) REFERENCES task_run (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS run_image (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_run_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    run_log_id BIGINT,
    storage_key VARCHAR(512) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    byte_size BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    width INTEGER,
    height INTEGER,
    captured_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_run_image_storage (storage_key),
    KEY idx_run_image_account_time (account_id, captured_at),
    KEY idx_run_image_run_time (task_run_id, captured_at),
    CONSTRAINT fk_run_image_run FOREIGN KEY (task_run_id) REFERENCES task_run (id),
    CONSTRAINT fk_run_image_log FOREIGN KEY (run_log_id) REFERENCES run_log (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS audit_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    actor_type VARCHAR(32) NOT NULL,
    actor_id BIGINT,
    event_type VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(128),
    result VARCHAR(32) NOT NULL,
    summary TEXT,
    metadata_json LONGTEXT,
    ip_hash CHAR(64),
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_actor_time (actor_type, actor_id, created_at),
    KEY idx_audit_resource_time (resource_type, resource_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
