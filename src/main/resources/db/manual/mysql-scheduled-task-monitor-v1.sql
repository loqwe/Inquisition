CREATE TABLE IF NOT EXISTS scheduled_task_runtime (
    task_key VARCHAR(64) NOT NULL,
    running TINYINT NOT NULL DEFAULT 0,
    last_outcome VARCHAR(16) NULL,
    last_trigger_source VARCHAR(32) NULL,
    last_started_at DATETIME(6) NULL,
    last_finished_at DATETIME(6) NULL,
    last_success_at DATETIME(6) NULL,
    last_failure_at DATETIME(6) NULL,
    next_run_at DATETIME(6) NULL,
    last_duration_ms BIGINT NULL,
    consecutive_failures INT NOT NULL DEFAULT 0,
    run_count BIGINT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (task_key),
    KEY idx_scheduled_task_runtime_next (running, next_run_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
