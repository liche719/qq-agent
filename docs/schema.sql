-- ============================================================
-- 参考 DDL（文档用途，**不会自动执行**）
--
-- 生产用的是 production profile → `ddl-auto: validate`：Hibernate **只校验不改表**，
-- 表/列缺一个或类型对不上，应用直接起不来（`Schema-validation: missing table ...`）。
-- 所以换一台机器、换一个库部署时，必须先用本文件把库建好，再启动应用。
-- 本机 local profile 是 `ddl-auto: update`（会自动补表补列），仅限开发用。
-- Quartz 的 11 张 QRTZ_ 表由 `spring.quartz.jdbc.initialize-schema=never` 之外的
-- 首次初始化创建，**之后必须保持 never**（见 AGENTS 第 5 节第 2 条，别改回 always）。
--
-- 文件末尾的 ALTER TABLE 是给"已经存在的库"补列的：**执行前先确认列是否已存在**，
-- 重复 ALTER 会报 Duplicate column name。
--
-- 两处容易踩的细节：
-- ① 布尔列必须是 `BIT(1)`：Hibernate 把 Java `Boolean` 默认映射成 `bit`，写成 `TINYINT(1)`
--    在 validate 下会报类型不匹配。本文件里早期手写的 TINYINT(1) 只作展示，建库请以线上库
--    `show create table` 的结果为准。
-- ② 历史升级用过的 ALTER（既有库补列）：
--    user_core_memory: ADD importance INT DEFAULT 5, keywords VARCHAR(1000),
--                      last_decision_at DATETIME, superseded_by_id BIGINT
--    user_work_memory: ADD importance INT DEFAULT 3, keywords VARCHAR(1000),
--                      last_decision_at DATETIME, superseded_by_id BIGINT
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

-- ============================================================
-- 2026-09-12 之后新增的表与列（2026-09-13 从线上库逐字核对补全）
-- production profile 是 ddl-auto=validate，**这几张表/列缺一个，换库启动就会失败**
-- （`Schema-validation: missing table [interview_round]` 之类），所以必须在这里写全。
-- ============================================================

-- 面试陪练的评分卡：一轮一条记录，复盘报告由程序按这些行生成
CREATE TABLE IF NOT EXISTS interview_round (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id        VARCHAR(128) NOT NULL,
    session_id     VARCHAR(40),          -- 一次练习的标识，开始陪练时生成
    role           VARCHAR(120),         -- 本轮岗位（用户开始练习时说的，可为空）
    seq            INT,                  -- 第几轮，从 1 开始
    category       VARCHAR(32),          -- 题类：自我介绍/项目深挖/技术基础/系统设计/行为面试/反问环节
    question       VARCHAR(600),
    answer_summary VARCHAR(1200),
    score_content  INT,                  -- 四维评分 1~5
    score_structure INT,
    score_depth    INT,
    score_delivery INT,
    feedback       VARCHAR(800),
    created_at     DATETIME,
    INDEX idx_interview_user_session (user_id, session_id)
);

-- 定时任务（到点跑一遍完整 Agent 并把结果发回）：调度本身复用 Quartz 的 QRTZ_* 表
CREATE TABLE IF NOT EXISTS scheduled_task (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id     VARCHAR(128) NOT NULL,
    title       VARCHAR(120),
    instruction VARCHAR(2000),           -- 到点执行的指令（截断要为省略号留一位，列宽刚好 2000）
    cron        VARCHAR(64) NOT NULL,    -- Quartz 6 段：秒 分 时 日 月 周
    enabled     BIT(1) DEFAULT b'1',
    next_run_at DATETIME,
    last_run_at DATETIME,
    status      VARCHAR(16),             -- IDLE/RUNNING/SUCCESS/FAILED
    last_result VARCHAR(2000),           -- 上一次执行结果摘要（就是发给用户的那条）
    last_error  VARCHAR(500),
    run_count   INT DEFAULT 0,
    created_at  DATETIME,
    updated_at  DATETIME,
    INDEX idx_scheduled_user (user_id),
    INDEX idx_scheduled_enabled (enabled)
);

-- 墨墨背单词接入的键值设置表（Token 存这里时优先于环境变量）
CREATE TABLE IF NOT EXISTS maimemo_setting (
    setting_key   VARCHAR(64) PRIMARY KEY,
    setting_value VARCHAR(2048),
    updated_at    DATETIME
);

-- 面试陪练只在 user_profile 上加三列（不动用户人设）
ALTER TABLE user_profile
    ADD COLUMN coach_mode       VARCHAR(32),
    ADD COLUMN coach_session_id VARCHAR(40),
    ADD COLUMN coach_role       VARCHAR(120);

-- 情景记忆见 deploy/mysql/V2__create_episodic_memory.sql（那份是从线上库核对过的，别在这里手抄一份猜的）

