-- 自主模块（self）：它自己那一侧的状态。**四张表全是新增，不动任何既有表**。
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

-- 反思产物（二期写，一期只建表）
CREATE TABLE IF NOT EXISTS agent_reflection (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    level           INT          NOT NULL DEFAULT 1,
    input_event_ids VARCHAR(500),                    -- 证据链：这次反思读了哪几条事件
    conclusion      VARCHAR(1000) NOT NULL,
    importance      INT          NOT NULL DEFAULT 0,
    written_back    BIGINT,                          -- 写回了哪个块（agent_self_block.id）
    created_at      DATETIME     NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_self_reflection_level (level, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
