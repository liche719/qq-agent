-- 记忆提取审计：每跑一次（含跳过/失败）留一行，"到底提没提取成功"从猜变成可查
-- 2026-09-17；对应 docs/memory-hybrid-plan.md 步 1
-- 注意：生产 profile 是 ddl-auto=validate —— 必须先建表再部署，否则容器起不来

CREATE TABLE IF NOT EXISTS memory_extraction_run (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    user_id           VARCHAR(128)  NOT NULL,
    trigger_source    VARCHAR(32)   NOT NULL DEFAULT 'AUTO',
    window_turns      INT           NOT NULL DEFAULT 0,
    window_chars      INT           NOT NULL DEFAULT 0,
    prompt_tokens     INT           NOT NULL DEFAULT 0,
    completion_tokens INT           NOT NULL DEFAULT 0,
    reasoning_tokens  INT           NOT NULL DEFAULT 0,
    cache_hit_tokens  BIGINT        NOT NULL DEFAULT 0,
    cache_miss_tokens BIGINT        NOT NULL DEFAULT 0,
    cost_yuan         DECIMAL(10,4) NOT NULL DEFAULT 0,
    duration_ms       INT           NOT NULL DEFAULT 0,
    verdict_json      VARCHAR(512)  NULL,
    written_ids       VARCHAR(512)  NULL,
    skip_reason       VARCHAR(64)   NULL,
    created_at        DATETIME      NOT NULL,
    PRIMARY KEY (id),
    KEY idx_mer_user_time (user_id, created_at),
    KEY idx_mer_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
