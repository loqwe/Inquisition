CREATE TABLE IF NOT EXISTS admin (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT,
    password TEXT,
    permission TEXT,
    notice TEXT,
    `delete` INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS pro_user (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT,
    password TEXT,
    permission TEXT,
    balance REAL,
    discount REAL,
    authorization TEXT,
    expire_time DATETIME,
    `delete` INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS account (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT,
    account TEXT,
    password TEXT,
    password_ciphertext TEXT,
    password_verifier TEXT,
    freeze INTEGER DEFAULT 0,
    server INTEGER DEFAULT 0,
    task_type TEXT DEFAULT 'daily',
    config TEXT,
    active TEXT,
    notice TEXT,
    b_limit_device TEXT,
    refresh INTEGER DEFAULT 1,
    agent INTEGER,
    create_time DATETIME,
    update_time DATETIME,
    expire_time DATETIME,
    `delete` INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS device (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_name TEXT,
    device_role TEXT DEFAULT 'BACKUP',
    device_token TEXT,
    work_scope TEXT,
    chinac INTEGER,
    region TEXT,
    expire_time DATETIME,
    `delete` INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    level TEXT,
    task_type TEXT,
    title TEXT,
    detail TEXT,
    image_url TEXT,
    `from` TEXT,
    server INTEGER,
    name TEXT,
    account TEXT,
    password TEXT,
    time DATETIME,
    `delete` INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS bill (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    order_no TEXT,
    platform_order_no TEXT,
    pay_type TEXT,
    pay_url TEXT,
    type TEXT,
    param TEXT,
    user_id INTEGER,
    amount REAL,
    actual_pay_amount REAL,
    state INTEGER,
    update_time DATETIME
);

CREATE TABLE IF NOT EXISTS cdk (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    cdk TEXT,
    type TEXT,
    param TEXT,
    tag TEXT,
    is_agent INTEGER,
    agent INTEGER,
    used INTEGER
);

CREATE TABLE IF NOT EXISTS goods (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT,
    value TEXT,
    type TEXT,
    params TEXT,
    description TEXT,
    price REAL,
    on_sale INTEGER
);

CREATE TABLE IF NOT EXISTS task_definition (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    task_config TEXT NOT NULL,
    schedule_type TEXT NOT NULL DEFAULT 'MANUAL',
    schedule_expression TEXT,
    sort_order INTEGER NOT NULL DEFAULT 0,
    version INTEGER NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    deleted INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS task_run (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_definition_id INTEGER,
    account_id INTEGER NOT NULL,
    device_id INTEGER,
    run_key TEXT NOT NULL UNIQUE,
    attempt_id TEXT NOT NULL UNIQUE,
    lease_id TEXT,
    status TEXT NOT NULL,
    trigger_type TEXT NOT NULL,
    config_snapshot TEXT NOT NULL,
    lease_expires_at DATETIME,
    started_at DATETIME,
    finished_at DATETIME,
    error_code TEXT,
    error_message TEXT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    FOREIGN KEY (task_definition_id) REFERENCES task_definition(id)
);

CREATE TABLE IF NOT EXISTS run_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_run_id INTEGER NOT NULL,
    account_id INTEGER NOT NULL,
    sequence_no INTEGER NOT NULL,
    level TEXT NOT NULL,
    phase TEXT,
    message TEXT NOT NULL,
    context_json TEXT,
    occurred_at DATETIME NOT NULL,
    UNIQUE (task_run_id, sequence_no),
    FOREIGN KEY (task_run_id) REFERENCES task_run(id)
);

CREATE TABLE IF NOT EXISTS run_image (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_run_id INTEGER NOT NULL,
    account_id INTEGER NOT NULL,
    run_log_id INTEGER,
    storage_key TEXT NOT NULL UNIQUE,
    content_type TEXT NOT NULL,
    byte_size INTEGER NOT NULL,
    sha256 TEXT NOT NULL,
    width INTEGER,
    height INTEGER,
    captured_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    FOREIGN KEY (task_run_id) REFERENCES task_run(id),
    FOREIGN KEY (run_log_id) REFERENCES run_log(id)
);

CREATE TABLE IF NOT EXISTS audit_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    actor_type TEXT NOT NULL,
    actor_id INTEGER,
    event_type TEXT NOT NULL,
    resource_type TEXT NOT NULL,
    resource_id TEXT,
    result TEXT NOT NULL,
    summary TEXT,
    metadata_json TEXT,
    ip_hash TEXT,
    created_at DATETIME NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_task_definition_account ON task_definition (account_id, deleted, enabled);
CREATE INDEX IF NOT EXISTS idx_task_run_dispatch ON task_run (status, lease_expires_at, created_at);
CREATE INDEX IF NOT EXISTS idx_task_run_account_time ON task_run (account_id, created_at);
CREATE INDEX IF NOT EXISTS idx_task_run_device_time ON task_run (device_id, created_at);
CREATE INDEX IF NOT EXISTS idx_run_log_account_time ON run_log (account_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_run_log_run_time ON run_log (task_run_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_run_image_account_time ON run_image (account_id, captured_at);
CREATE INDEX IF NOT EXISTS idx_run_image_run_time ON run_image (task_run_id, captured_at);
CREATE INDEX IF NOT EXISTS idx_audit_actor_time ON audit_event (actor_type, actor_id, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_resource_time ON audit_event (resource_type, resource_id, created_at);

CREATE INDEX IF NOT EXISTS idx_admin_delete ON admin (`delete`);
CREATE INDEX IF NOT EXISTS idx_pro_user_username ON pro_user (username);
CREATE INDEX IF NOT EXISTS idx_pro_user_authorization ON pro_user (authorization);
CREATE INDEX IF NOT EXISTS idx_pro_user_delete ON pro_user (`delete`);
CREATE INDEX IF NOT EXISTS idx_account_account ON account (account);
CREATE INDEX IF NOT EXISTS idx_account_agent ON account (agent);
CREATE INDEX IF NOT EXISTS idx_account_dispatch ON account (`delete`, freeze, task_type, expire_time);
CREATE INDEX IF NOT EXISTS idx_device_token ON device (device_token);
CREATE INDEX IF NOT EXISTS idx_device_delete ON device (`delete`);
CREATE INDEX IF NOT EXISTS idx_log_account_time ON log (account, time);
CREATE INDEX IF NOT EXISTS idx_log_delete_time ON log (`delete`, time);
CREATE INDEX IF NOT EXISTS idx_bill_order_no ON bill (order_no);
CREATE INDEX IF NOT EXISTS idx_cdk_cdk ON cdk (cdk);
CREATE INDEX IF NOT EXISTS idx_cdk_tag ON cdk (tag);
CREATE INDEX IF NOT EXISTS idx_goods_value ON goods (value);
