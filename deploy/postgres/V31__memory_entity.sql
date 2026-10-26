-- 2026-10-26 P0-5 实体归一：同一件事的 subject 有多种写法（"周二晚·课程" / "周二晚 课程"），
-- 会拆成多张卡、破坏取代链。这里存 canonical（规范写法）+ aliases（见过的其它写法）。
CREATE TABLE IF NOT EXISTS memory_entity (
    id          bigserial PRIMARY KEY,
    user_id     varchar(128) NOT NULL,
    canonical   varchar(200) NOT NULL,
    aliases     varchar(2000),
    fact_count  integer      NOT NULL DEFAULT 0,
    created_at  timestamp(6) NOT NULL,
    updated_at  timestamp(6) NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_entity_user_canonical
    ON memory_entity (user_id, canonical);

CREATE INDEX IF NOT EXISTS idx_entity_user_updated
    ON memory_entity (user_id, updated_at DESC);
