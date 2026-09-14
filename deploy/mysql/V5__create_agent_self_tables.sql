-- 自主模块（self）：它自己那一侧的状态。**五张表全是新增，不动任何既有表**。
-- 生产是 ddl-auto=validate，实体与本文件必须严格对齐；**必须先建表再部署**。
-- 应用方式：
--   docker exec -i wechat-agent-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 \
--     wechat_agent < V5__create_agent_self_tables.sql
--
-- 设计要点（详见 docs/self-layer.md / self-layer-spec.md / self-layer-plan.md）：
--   1. **没有 user_id**：这一侧属于"它自己"（agent 维度），不属于某一段对话、也不属于某个用户
--      （先例原话：memory belongs to the agent, not to a single conversation）。读写由服务层限机主。
--   2. 块 = 常驻上下文里那一小块（有长度上限）；会累积的东西进 agent_self_event，不往块里塞。
--   3. 事件只追加，不修改不删除 —— 它是以后"倾向"的证据链。
--   4. agent_reflection 一期先建表（不写），二期反思流程才用，避免二期再发一次迁移。

-- 它自己的块：Persona / TASK / PROJECT / STANCE / NOTE
CREATE TABLE IF NOT EXISTS agent_self_block (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    block_type      VARCHAR(16)  NOT NULL,           -- PERSONA/TASK/PROJECT/STANCE/NOTE
    label           VARCHAR(64)  NOT NULL,
    value           VARCHAR(4000),
    char_limit      INT          NOT NULL DEFAULT 1200,
    description     VARCHAR(200),
    version         INT          NOT NULL DEFAULT 1,
    created_at      DATETIME,
    updated_at      DATETIME,
    PRIMARY KEY (id),
    UNIQUE KEY uk_self_block (block_type, label)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 它自己那侧的时间线（也是以后"倾向"的证据链；只追加）
CREATE TABLE IF NOT EXISTS agent_self_event (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    kind            VARCHAR(24)  NOT NULL,           -- GOAL_SET/GOAL_CLOSED/COMMIT/COMMIT_RESOLVED/JUDGE/DISAGREE/REFLECT/NOTE
    topic           VARCHAR(60),                     -- 判断的类别（以后倾向按它归类）
    stance          VARCHAR(16),                     -- 方向（A/B/中立）
    content         VARCHAR(1000) NOT NULL,
    evidence        VARCHAR(300),                    -- 引用：对话 id 或事件 id；写入时服务层校验必须真实存在
    importance      INT          NOT NULL DEFAULT 0,
    created_at      DATETIME     NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_self_event_kind (kind, created_at),
    INDEX idx_self_event_topic (topic, stance, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 账：它许过的诺、做过的预测（"得失"的落点）
CREATE TABLE IF NOT EXISTS agent_commitment (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    content         VARCHAR(500) NOT NULL,
    due_at          DATETIME,
    status          VARCHAR(16)  NOT NULL DEFAULT 'OPEN',   -- OPEN/KEPT/BROKEN/ABANDONED
    evidence        VARCHAR(300),
    resolved_at     DATETIME,
    created_at      DATETIME,
    updated_at      DATETIME,
    PRIMARY KEY (id),
    INDEX idx_self_commitment_status (status, due_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 反思产物（二期写：反思流程的每次产出与成本）
CREATE TABLE IF NOT EXISTS agent_reflection (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    level           INT          NOT NULL DEFAULT 1,
    trigger_type    VARCHAR(16),                     -- step-count / manual / compaction-event
    input_event_ids VARCHAR(500),                    -- 证据链：这次反思读了哪几条事件
    conclusion      VARCHAR(1000) NOT NULL,
    importance      INT          NOT NULL DEFAULT 0,
    written_back    BIGINT,                          -- 写回了哪个块（agent_self_block.id）
    calls           INT          NOT NULL DEFAULT 1, -- 成本账：调用次数
    prompt_chars    INT          NOT NULL DEFAULT 0,
    response_chars  INT          NOT NULL DEFAULT 0,
    prompt_tokens   INT          NOT NULL DEFAULT 0,
    completion_tokens INT        NOT NULL DEFAULT 0,
    duration_ms     INT          NOT NULL DEFAULT 0,
    created_at      DATETIME     NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_self_reflection_level (level, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 倾向（§5 判断→倾向）：**由程序按规则提升，不是模型自己写**
-- 为什么单独一张表而不是只写进 STANCE 块：块是**注入用的投影**（≤5 行、受 800 字预算约束），
-- 而倾向还要带证据区间、反例计数、修订次数、FSRS 的 S/D 与复查时间——那些进块只会挤掉对话预算。
-- 所以：agent_stance 是事实源，STANCE 块由它渲染（对应文档 §3「常驻要小、累积走投影」）。
CREATE TABLE IF NOT EXISTS agent_stance (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    topic           VARCHAR(60)  NOT NULL,           -- 判断类别（与 agent_self_event.topic 对齐）
    direction       VARCHAR(16)  NOT NULL,           -- 方向标签（它自己起的短词，如 A/B/要有记录）
    content         VARCHAR(600) NOT NULL,           -- 倾向原话（必须含证据区间说明）
    evidence_ids    VARCHAR(300),                    -- 证据区间：支撑它的 JUDGE 事件 id（逗号分隔）
    counter_ids     VARCHAR(300),                    -- 反例：方向相反的 JUDGE 事件 id（反例优先，必须看得见）
    support_count   INT          NOT NULL DEFAULT 0,
    counter_count   INT          NOT NULL DEFAULT 0,
    revise_count    INT          NOT NULL DEFAULT 0,
    stability       DOUBLE       NOT NULL DEFAULT 1, -- FSRS 的 S（记忆强度，单位：天）
    difficulty      DOUBLE       NOT NULL DEFAULT 5, -- FSRS 的 D（1~10，越大越难）
    last_review_at  DATETIME,
    next_review_at  DATETIME,                        -- 由 R(t,S) 掉到目标保留率反推，不是拍脑袋
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE/RETIRED/DEMOTED/REVISED
    formed_at       DATETIME     NOT NULL,
    updated_at      DATETIME,
    PRIMARY KEY (id),
    INDEX idx_self_stance_status (status, next_review_at),
    INDEX idx_self_stance_topic (topic, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
