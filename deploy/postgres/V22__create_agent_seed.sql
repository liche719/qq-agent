-- 「它自己的念头」（2026-10-20）
--
-- 用户的话：「自主模块里的能力是相互知道的，就像人一样，相辅相成——刷了个视频 → 有了想法 →
-- 研究 → 又刷特定视频深入了解 → 再去做别的」。
--
-- 现有的对象都太重：`agent_quest` 是成型的方向（要开题、要推进），`agent_quest_note` 是结论，
-- `agent_self_event` 是流水账。缺一种**轻量**的东西：一句话的"我想搞清楚 X，因为刚才看到 Y"，
-- 它还没成形、可以被任何活动产生、也可以被任何活动接着做。
--
-- 状态：OPEN（还没消化的念头）/ DONE（已经搞清或做完了）/ DROPPED（不做了）/ QUEST（升格成了方向）。
CREATE TABLE IF NOT EXISTS agent_seed (
    id         BIGSERIAL    PRIMARY KEY,
    content    TEXT         NOT NULL DEFAULT '',
    source     VARCHAR(64)  NOT NULL DEFAULT '',
    status     VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    quest_id   BIGINT       NOT NULL DEFAULT 0,
    created_at TIMESTAMP    NOT NULL,
    updated_at TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_agent_seed_status ON agent_seed (status, created_at DESC);

COMMENT ON TABLE agent_seed IS '它自己的念头：轻量、可被任何自主活动产生和消费，是活动之间"相辅相成"的接头';
