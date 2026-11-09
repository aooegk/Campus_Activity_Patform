-- 校园活动组队与候补平台 · MySQL 8 建表脚本（与 schema.sql 同规则）
-- 与 PostgreSQL 版一一对应：6 张表、容量/唯一/邀请/候补硬规则、中文分类 CHECK 含“其他”。
-- MySQL 8 方言要点：
--   * BIGINT AUTO_INCREMENT 代替 BIGSERIAL；DATETIME DEFAULT NOW() 代替 TIMESTAMP DEFAULT now()
--   * MySQL 不支持 CREATE INDEX IF NOT EXISTS，且 Spring 的 sql init 每次启动都会跑脚本，
--     索引必须定义在 CREATE TABLE 内部，表用 CREATE TABLE IF NOT EXISTS，
--     这样重复执行不报错；UNIQUE 约束同样放表内。
--   * CHECK 约束保留（MySQL 8.0.16+ 支持）；char_length() MySQL 同名可用。
--   * 显式 ENGINE=InnoDB（外键生效）与 utf8mb4（中文分类/昵称不乱码）。
CREATE TABLE IF NOT EXISTS app_user (
    id            BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    account       VARCHAR(32) NOT NULL UNIQUE CHECK (char_length(account) BETWEEN 4 AND 32),
    password_hash VARCHAR(255) NOT NULL,
    nickname      VARCHAR(30) NOT NULL CHECK (char_length(nickname) BETWEEN 1 AND 30),
    contact       VARCHAR(100) NOT NULL DEFAULT '',
    role          VARCHAR(10) NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    status        VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at    DATETIME NOT NULL DEFAULT NOW()
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS activity (
    id           BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    organizer_id BIGINT NOT NULL REFERENCES app_user(id),
    title        VARCHAR(50) NOT NULL CHECK (char_length(title) BETWEEN 1 AND 50),
    category     VARCHAR(10) NOT NULL CHECK (category IN ('羽毛球', '桌游', '学习组', '其他')),
    description  TEXT NOT NULL CHECK (char_length(description) <= 2000),
    conditions   VARCHAR(500) NOT NULL DEFAULT '' CHECK (char_length(conditions) <= 500),
    location     VARCHAR(100) NOT NULL CHECK (char_length(location) BETWEEN 1 AND 100),
    capacity     INT NOT NULL CHECK (capacity BETWEEN 1 AND 100),
    deadline     DATETIME NOT NULL,
    start_time   DATETIME NOT NULL,
    end_time     DATETIME NOT NULL,
    status       VARCHAR(10) NOT NULL DEFAULT 'OPEN'
                 CHECK (status IN ('OPEN', 'READY', 'RUNNING', 'ENDED', 'CANCELLED')),
    created_at   DATETIME NOT NULL DEFAULT NOW(),
    updated_at   DATETIME NOT NULL DEFAULT NOW(),
    CHECK (deadline <= start_time AND start_time < end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS participation (
    id          BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    activity_id BIGINT NOT NULL REFERENCES activity(id),
    user_id     BIGINT NOT NULL REFERENCES app_user(id),
    status      VARCHAR(10) NOT NULL CHECK (status IN
                    ('FORMAL', 'WAITING', 'INVITED', 'WITHDRAWN', 'EXPIRED', 'CANCELLED')),
    queued_at   DATETIME,
    joined_at   DATETIME,
    updated_at  DATETIME NOT NULL DEFAULT NOW(),
    UNIQUE (activity_id, user_id),
    INDEX idx_part_activity_status (activity_id, status, queued_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS invitation (
    id               BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    participation_id BIGINT NOT NULL REFERENCES participation(id),
    status           VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN
                         ('PENDING', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'INVALID')),
    issued_at        DATETIME NOT NULL,
    expires_at       DATETIME NOT NULL,
    responded_at     DATETIME,
    event_key        VARCHAR(100) NOT NULL UNIQUE,
    CHECK (issued_at < expires_at),
    INDEX idx_inv_status_expires (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS notification (
    id            BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES app_user(id),
    activity_id   BIGINT REFERENCES activity(id),
    invitation_id BIGINT REFERENCES invitation(id),
    type          VARCHAR(10) NOT NULL CHECK (type IN ('报名', '候补', '邀请', '变更', '退出', '取消', '留言')),
    content       TEXT NOT NULL CHECK (char_length(content) <= 1000),
    event_key     VARCHAR(100) NOT NULL,
    sender_id     BIGINT NULL REFERENCES app_user(id),
    read_at       DATETIME,
    created_at    DATETIME NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, event_key),
    INDEX idx_notif_user_created (user_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS feedback (
    id               BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    participation_id BIGINT NOT NULL UNIQUE REFERENCES participation(id),
    score            INT NOT NULL CHECK (score BETWEEN 1 AND 5),
    content          VARCHAR(500) NOT NULL DEFAULT '' CHECK (char_length(content) <= 500),
    created_at       DATETIME NOT NULL DEFAULT NOW()
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
