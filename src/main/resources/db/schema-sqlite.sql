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
