-- 三期领域②：**它自己的方向** —— 「它拥有自己的想法想去做什么」。
-- 应用方式（生产必须先建表再部署，ddl-auto=validate）：
--   docker exec -i wechat-agent-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 \
--     wechat_agent < V7__create_agent_quest_tables.sql
--
-- 设计要点（docs/self-layer-spec.md §7/§9/§9.3）：
--   1. **这块地盘是它自己的，不是给机主办事**：题目、选择理由、笔记都由它自己出；
--      产出只落这三张表，不写 conversation_memory、不进 user_core_memory、不发给机主。
--   2. **每天一笔预算 + 稀缺**：作业记录进 agent_quest_run，预算按"今天跑了几次"算；
--      同时只允许一个 ACTIVE 领域（§9：开新的必须关旧的）。
--   3. **标尺是"更新条数"与"被自己撤回的比例"**（§9.3）：撤回说明它在核对，而不是在堆料。
--      所以 retracted_at / retract_reason 是一等公民，不是软删除标记。
--   4. **来源必须落 URL**：没有来源的"笔记"就是资料搬运，面板按 source_url 是否为空单独计数。
CREATE TABLE IF NOT EXISTS agent_quest (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    title         VARCHAR(200) NOT NULL,                        -- 领域名（它自己起的）
    why           VARCHAR(600) NOT NULL,                        -- 为什么选这个（它自己的理由）
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',       -- ACTIVE/PAUSED/CLOSED
    next_step     VARCHAR(600),                                 -- 它自己写的"下一步"
    step_count    INT          NOT NULL DEFAULT 0,
    note_count    INT          NOT NULL DEFAULT 0,
    retract_count INT          NOT NULL DEFAULT 0,
    evidence      VARCHAR(300),
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME,
    closed_at     DATETIME,
    PRIMARY KEY (id),
    INDEX idx_self_quest_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_quest_note (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    quest_id       BIGINT        NOT NULL,
    content        VARCHAR(2000) NOT NULL,
    source_url     VARCHAR(1000),                               -- 带来源的更新才算数
    source_title   VARCHAR(300),
    retracted_at   DATETIME,                                    -- 自己撤回：过时结论
    retract_reason VARCHAR(300),
    evidence       VARCHAR(300),
    created_at     DATETIME      NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_self_quest_note_quest (quest_id, created_at),
    INDEX idx_self_quest_note_recent (created_at, retracted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_quest_run (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    quest_id          BIGINT,
    status            VARCHAR(16)   NOT NULL,                   -- RAN/SKIPPED/FAILED
    reason            VARCHAR(500),
    summary           VARCHAR(2000),                            -- 它这一轮自己写下的东西
    step_count        INT           NOT NULL DEFAULT 0,
    note_count        INT           NOT NULL DEFAULT 0,
    retract_count     INT           NOT NULL DEFAULT 0,
    prompt_tokens     INT           NOT NULL DEFAULT 0,         -- 成本入账（坑 60：思考 token 也算）
    completion_tokens INT           NOT NULL DEFAULT 0,
    duration_ms       INT           NOT NULL DEFAULT 0,
    created_at        DATETIME      NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_self_quest_run_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
