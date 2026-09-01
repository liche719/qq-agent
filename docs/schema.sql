-- ============================================================
-- 参考 DDL（文档用途）
-- 本机 local profile 使用 JPA ddl-auto=update 自动创建或补充下列字段；Quartz 的 11 张 QRTZ_ 表
-- 由 spring.quartz.jdbc.initialize-schema=always 自动创建。
-- production profile 使用 ddl-auto=validate，不会自动迁移：升级前请由管理员执行本文件中新增的
-- conversation_memory 建表语句，以及对既有 user_core_memory / user_work_memory 的下列 ALTER TABLE：
--   ADD COLUMN importance INT DEFAULT 3; ADD COLUMN keywords VARCHAR(1000);
--   ADD COLUMN last_decision_at DATETIME; ADD COLUMN superseded_by_id BIGINT.
-- 核心记忆的 importance 默认值应为 5。执行前请按当前表结构确认列是否已存在，避免重复 ALTER。
-- ============================================================

CREATE TABLE IF NOT EXISTS user_profile (
    user_id    VARCHAR(128) PRIMARY KEY,
    persona    VARCHAR(4000),
    memory_enabled TINYINT(1) DEFAULT 1,
    proactive_care_enabled TINYINT(1) DEFAULT 0,
    proactive_care_cadence VARCHAR(16) DEFAULT 'WEEKLY',
    next_care_at DATETIME,
    last_care_at DATETIME,
    last_channel VARCHAR(32),
    last_bot_id VARCHAR(128),
    last_seen_at DATETIME,
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
    importance INT DEFAULT 5,
    keywords VARCHAR(1000),
    last_confirmed_at DATETIME,
    last_used_at DATETIME,
    last_decision_at DATETIME,
    superseded_by_id BIGINT,
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
    importance INT DEFAULT 3,
    keywords VARCHAR(1000),
    valid_from DATETIME,
    valid_until DATETIME,
    last_confirmed_at DATETIME,
    last_used_at DATETIME,
    last_decision_at DATETIME,
    superseded_by_id BIGINT,
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

CREATE TABLE IF NOT EXISTS conversation_memory (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id             VARCHAR(128) NOT NULL,
    role                VARCHAR(16) NOT NULL,
    event_key           VARCHAR(128),
    content             LONGTEXT NOT NULL,
    source_message_ids  VARCHAR(2000),
    source_media_ids    VARCHAR(1000),
    created_at          DATETIME(6),
    expires_at          DATETIME(6),
    UNIQUE KEY uq_conversation_user_event (user_id, event_key),
    INDEX idx_conversation_user_created (user_id, created_at),
    INDEX idx_conversation_expires (expires_at)
);

CREATE TABLE IF NOT EXISTS stored_media (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(128) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    original_name VARCHAR(255),
    content_type VARCHAR(128),
    relative_path VARCHAR(1024) NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    size_bytes BIGINT,
    summary VARCHAR(2000),
    importance_reason VARCHAR(1000),
    extracted_text LONGTEXT,
    source_message_id VARCHAR(255),
    source_url VARCHAR(2048),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME,
    updated_at DATETIME,
    trashed_at DATETIME,
    trash_reason VARCHAR(1000),
    INDEX idx_media_user_status (user_id, status),
    INDEX idx_media_user_hash (user_id, sha256)
);

CREATE TABLE IF NOT EXISTS reminder_task (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         VARCHAR(128) NOT NULL,
    content         VARCHAR(2000),
    trigger_at      DATETIME,
    prewarm_minutes INT DEFAULT 10,
    cron            VARCHAR(64),
    status          VARCHAR(32) DEFAULT 'PENDING',  -- PENDING/COMPLETED/CANCELLED/EXPIRED
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
