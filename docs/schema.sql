-- ============================================================
-- 参考 DDL（文档用途）
-- 实际表结构由 JPA ddl-auto=update 自动创建；Quartz 的 11 张 QRTZ_ 表
-- 由 spring.quartz.jdbc.initialize-schema=always 自动创建。
-- ============================================================

CREATE TABLE IF NOT EXISTS user_profile (
    user_id    VARCHAR(128) PRIMARY KEY,
    persona    VARCHAR(4000),
    created_at DATETIME,
    updated_at DATETIME
);

CREATE TABLE IF NOT EXISTS user_core_memory (
    id         BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id    VARCHAR(128) NOT NULL,
    content    VARCHAR(4000),
    status     VARCHAR(32) DEFAULT 'ACTIVE',
    source_type VARCHAR(32) DEFAULT 'USER_EXPLICIT',
    confidence INT DEFAULT 100,
    last_confirmed_at DATETIME,
    last_used_at DATETIME,
    source_message_ids VARCHAR(2000),
    source_media_ids VARCHAR(1000),
    created_at DATETIME,
    updated_at DATETIME,
    INDEX idx_core_user (user_id)
);

CREATE TABLE IF NOT EXISTS user_work_memory (
    id         BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id    VARCHAR(128) NOT NULL,
    content    VARCHAR(2000),
    priority   INT DEFAULT 3,
    archived   TINYINT(1) DEFAULT 0,
    source     VARCHAR(32) DEFAULT 'extraction',
    status     VARCHAR(32) DEFAULT 'ACTIVE',
    source_type VARCHAR(32) DEFAULT 'USER_EXPLICIT',
    confidence INT DEFAULT 100,
    valid_from DATETIME,
    valid_until DATETIME,
    last_confirmed_at DATETIME,
    last_used_at DATETIME,
    source_message_ids VARCHAR(2000),
    source_media_ids VARCHAR(1000),
    created_at DATETIME,
    updated_at DATETIME,
    INDEX idx_work_user (user_id)
);

CREATE TABLE IF NOT EXISTS memory_archive (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id      VARCHAR(128) NOT NULL,
    summary      VARCHAR(4000),
    original_ids VARCHAR(2000),  -- JSON 数组
    created_at   DATETIME,
    INDEX idx_archive_user (user_id)
);

CREATE TABLE IF NOT EXISTS memory_change_log (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id        VARCHAR(128) NOT NULL,
    action         VARCHAR(32),   -- ADD/UPDATE/ARCHIVE/ARCHIVE_CREATE/CONFIRM_REJECT
    layer          VARCHAR(32),   -- CORE/WORK/ARCHIVE
    target_id      BIGINT,
    before_content VARCHAR(4000),
    after_content  VARCHAR(4000),
    reason         VARCHAR(512),
    operator       VARCHAR(32),   -- AUTO/USER/SYSTEM
    created_at     DATETIME,
    INDEX idx_changelog_user (user_id)
);

CREATE TABLE IF NOT EXISTS reminder_task (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         VARCHAR(128) NOT NULL,
    content         VARCHAR(2000),
    trigger_at      DATETIME,
    prewarm_minutes INT DEFAULT 10,
    cron            VARCHAR(64),
    status          VARCHAR(32) DEFAULT 'PENDING',  -- PENDING/COMPLETED/CANCELLED
    created_at      DATETIME,
    updated_at      DATETIME,
    INDEX idx_reminder_user (user_id)
);

CREATE TABLE IF NOT EXISTS operation_log (
    id         BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id    VARCHAR(128) NOT NULL,
    action     VARCHAR(64),
    detail     VARCHAR(2000),
    created_at DATETIME,
    INDEX idx_oplog_user (user_id)
);
