-- ============================================================
-- ⚠️⚠️ 历史文件，**不要执行**：这是 **2026-09-01 的 MySQL 8 迁移** ⚠️⚠️
--
-- 现状（2026-10-10）：
--   · 生产早已是 **PostgreSQL 16 + pgvector**（2026-09-18 整库迁过来）——下面这些
--     `TINYINT(1)` / `AUTO_INCREMENT` / `LONGTEXT` / `UNIQUE KEY` / `INDEX` 全是 MySQL 方言，
--     **拿到现在的库里跑一定失败**。
--   · `user_core_memory` / `user_work_memory` 两张表 **2026-09-19 已由 `V17` 删除**；记忆现在只有
--     `memory`（kind=PROFILE/TASK/EXPERIENCE）/ `memory_fact` / `conversation_memory` 三张
--     （见 `docs/memory-vector-plan.md` §20）。
--   · 现在要建库/补结构：`deploy/postgres/V11 ~ V19*.sql`；看完整表结构：`deploy/postgres/schema-generated.sql`。
--
-- 保留它的唯一价值：`conversation_memory` 与 `stored_media` 这两张核心表最早就是这份文件建的，
-- 想追溯"它们最初长什么样"时看这里。
-- ============================================================
--
-- 记忆演化迁移（MySQL 8.0.29+）
-- 仅供 production profile 使用：该 profile 的 Hibernate 为 validate，不会自动建表或加列。
-- 执行前先完成数据库备份；本迁移只新增表、列和索引，不修改或删除既有记忆数据。

ALTER TABLE user_profile
    ADD COLUMN IF NOT EXISTS memory_enabled TINYINT(1) DEFAULT 1,
    ADD COLUMN IF NOT EXISTS proactive_care_enabled TINYINT(1) DEFAULT 0,
    ADD COLUMN IF NOT EXISTS proactive_care_cadence VARCHAR(16) DEFAULT 'WEEKLY',
    ADD COLUMN IF NOT EXISTS next_care_at DATETIME,
    ADD COLUMN IF NOT EXISTS last_care_at DATETIME,
    ADD COLUMN IF NOT EXISTS last_channel VARCHAR(32),
    ADD COLUMN IF NOT EXISTS last_bot_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS last_seen_at DATETIME;

ALTER TABLE user_core_memory
    ADD COLUMN IF NOT EXISTS importance INT DEFAULT 5,
    ADD COLUMN IF NOT EXISTS keywords VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS last_decision_at DATETIME,
    ADD COLUMN IF NOT EXISTS superseded_by_id BIGINT;

ALTER TABLE user_work_memory
    ADD COLUMN IF NOT EXISTS importance INT DEFAULT 3,
    ADD COLUMN IF NOT EXISTS keywords VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS last_decision_at DATETIME,
    ADD COLUMN IF NOT EXISTS superseded_by_id BIGINT;

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
