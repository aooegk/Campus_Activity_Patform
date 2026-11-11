-- 校园活动组队与候补平台 · PostgreSQL 建表脚本（SRS 第 4 章）
-- 注意：PostgreSQL 中 USER 是保留字，故 SRS 的 user 表在此命名为 app_user，
-- 其余表名、字段、唯一约束、状态枚举与 SRS 一一对应。
-- 核心规则：有效占用 = 正式人数 + 未过期待确认邀请数，必须 <= capacity；
-- 同一活动同一用户只有一条当前参与记录；邀请终态只能转换一次。
CREATE TABLE IF NOT EXISTS app_user (
    id            BIGSERIAL PRIMARY KEY,
    account       VARCHAR(32) NOT NULL UNIQUE CHECK (char_length(account) BETWEEN 4 AND 32),
    password_hash VARCHAR(255) NOT NULL,
    nickname      VARCHAR(30) NOT NULL CHECK (char_length(nickname) BETWEEN 1 AND 30),
    contact       VARCHAR(100) NOT NULL DEFAULT '',
    role          VARCHAR(10) NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    status        VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS activity (
    id           BIGSERIAL PRIMARY KEY,
    organizer_id BIGINT NOT NULL REFERENCES app_user(id),
    title        VARCHAR(50) NOT NULL CHECK (char_length(title) BETWEEN 1 AND 50),
    category     VARCHAR(10) NOT NULL CHECK (category IN ('羽毛球', '桌游', '学习组', '其他')),
    description  TEXT NOT NULL DEFAULT '' CHECK (char_length(description) <= 2000),
    conditions   VARCHAR(500) NOT NULL DEFAULT '' CHECK (char_length(conditions) <= 500),
    location     VARCHAR(100) NOT NULL CHECK (char_length(location) BETWEEN 1 AND 100),
    capacity     INT NOT NULL CHECK (capacity BETWEEN 1 AND 100),
    deadline     TIMESTAMP NOT NULL,
    start_time   TIMESTAMP NOT NULL,
    end_time     TIMESTAMP NOT NULL,
    status       VARCHAR(10) NOT NULL DEFAULT 'OPEN'
                 CHECK (status IN ('OPEN', 'READY', 'RUNNING', 'ENDED', 'CANCELLED')),
    created_at   TIMESTAMP NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP NOT NULL DEFAULT now(),
    version      BIGINT NOT NULL DEFAULT 0,
    cancel_reason VARCHAR(500),
    CHECK (deadline <= start_time AND start_time < end_time)
);

CREATE TABLE IF NOT EXISTS participation (
    id          BIGSERIAL PRIMARY KEY,
    activity_id BIGINT NOT NULL REFERENCES activity(id),
    user_id     BIGINT NOT NULL REFERENCES app_user(id),
    status      VARCHAR(10) NOT NULL CHECK (status IN
                    ('FORMAL', 'WAITING', 'INVITED', 'WITHDRAWN', 'EXPIRED', 'CANCELLED')),
    queued_at   TIMESTAMP,
    joined_at   TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (activity_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_part_activity_status
    ON participation(activity_id, status, queued_at, id);

CREATE TABLE IF NOT EXISTS invitation (
    id               BIGSERIAL PRIMARY KEY,
    participation_id BIGINT NOT NULL REFERENCES participation(id),
    status           VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN
                         ('PENDING', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'INVALID')),
    issued_at        TIMESTAMP NOT NULL,
    expires_at       TIMESTAMP NOT NULL,
    responded_at     TIMESTAMP,
    event_key        VARCHAR(100) NOT NULL UNIQUE,
    CHECK (issued_at < expires_at)
);
CREATE INDEX IF NOT EXISTS idx_inv_status_expires ON invitation(status, expires_at);

CREATE TABLE IF NOT EXISTS notification (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES app_user(id),
    activity_id   BIGINT REFERENCES activity(id),
    invitation_id BIGINT REFERENCES invitation(id),
    type          VARCHAR(10) NOT NULL CHECK (type IN ('报名', '候补', '邀请', '变更', '退出', '取消', '留言')),
    content       TEXT NOT NULL CHECK (char_length(content) <= 1000),
    event_key     VARCHAR(100) NOT NULL,
    sender_id     BIGINT REFERENCES app_user(id),
    read_at       TIMESTAMP,
    created_at    TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (user_id, event_key)
);
CREATE INDEX IF NOT EXISTS idx_notif_user_created ON notification(user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS feedback (
    id               BIGSERIAL PRIMARY KEY,
    participation_id BIGINT NOT NULL UNIQUE REFERENCES participation(id),
    score            INT NOT NULL CHECK (score BETWEEN 1 AND 5),
    content          VARCHAR(500) NOT NULL DEFAULT '' CHECK (char_length(content) <= 500),
    created_at       TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS operation_receipt (
    user_id         BIGINT NOT NULL,
    key_hash        CHAR(64) NOT NULL,
    request_hash    CHAR(64) NOT NULL,
    response_status INTEGER,
    response_body   TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, key_hash),
    FOREIGN KEY (user_id) REFERENCES app_user(id)
);

CREATE TABLE IF NOT EXISTS login_attempt (
    user_id      BIGINT PRIMARY KEY,
    failed_count INTEGER NOT NULL,
    locked_until TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES app_user(id)
);

CREATE TABLE IF NOT EXISTS auth_session (
    token_hash CHAR(64) PRIMARY KEY,
    user_id    BIGINT NOT NULL,
    csrf_hash  CHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    idle_until TIMESTAMP NOT NULL,
    FOREIGN KEY (user_id) REFERENCES app_user(id)
);
CREATE INDEX IF NOT EXISTS idx_auth_session_user ON auth_session(user_id);

CREATE TABLE IF NOT EXISTS participation_event (
    id               BIGSERIAL PRIMARY KEY,
    participation_id BIGINT NOT NULL,
    from_status      VARCHAR(15),
    to_status        VARCHAR(15) NOT NULL,
    change_kind      VARCHAR(30) NOT NULL,
    occurred_at      TIMESTAMP NOT NULL,
    FOREIGN KEY (participation_id) REFERENCES participation(id)
);
CREATE INDEX IF NOT EXISTS idx_participation_event ON participation_event(participation_id, occurred_at, id);

CREATE TABLE IF NOT EXISTS attendance_activity (
    activity_id BIGINT PRIMARY KEY,
    code_seed   CHAR(64),
    snapshot_at TIMESTAMP,
    FOREIGN KEY (activity_id) REFERENCES activity(id)
);

CREATE TABLE IF NOT EXISTS attendance_record (
    activity_id  BIGINT NOT NULL,
    user_id      BIGINT NOT NULL,
    eligible     BOOLEAN NOT NULL DEFAULT FALSE,
    checked_in_at TIMESTAMP,
    failed_count INTEGER NOT NULL DEFAULT 0,
    failed_until TIMESTAMP,
    review_status VARCHAR(12),
    review_reason VARCHAR(500),
    version      BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (activity_id, user_id),
    FOREIGN KEY (activity_id) REFERENCES activity(id),
    FOREIGN KEY (user_id) REFERENCES app_user(id),
    CHECK (review_status IN ('PRESENT','ABSENT','EXCUSED','UNVERIFIED'))
);

CREATE TABLE IF NOT EXISTS attendance_review_event (
    activity_id BIGINT NOT NULL,
    user_id     BIGINT NOT NULL,
    version     BIGINT NOT NULL,
    reviewer_id BIGINT NOT NULL,
    status      VARCHAR(12) NOT NULL CHECK (status IN ('PRESENT','ABSENT','EXCUSED','UNVERIFIED')),
    reason      VARCHAR(500) NOT NULL,
    decided_at  TIMESTAMP NOT NULL,
    PRIMARY KEY (activity_id, user_id, version),
    FOREIGN KEY (activity_id, user_id) REFERENCES attendance_record(activity_id, user_id),
    FOREIGN KEY (reviewer_id) REFERENCES app_user(id)
);
