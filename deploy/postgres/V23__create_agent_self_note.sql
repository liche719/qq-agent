-- 「它自己的话」收成一张表（2026-10-20，大改第一步：只建表+搬数据，代码随后跟）
--
-- 动机：`agent_self_block`（块的当前值）与 `agent_self_event`（块/判断/反思的历史流水）
-- 其实是**同一件事的两个投影**——块的每次更新本来就会写一条事件。两张表还各带一个 repo、
-- 各一套方法，工具层因此长出 selfAppend/selfReplace/selfSummarize 三个"差不多"的东西。
--
-- 目标模型：一句话 + 类型 + 槽位 + 证据。
--   kind      ：BLOCK / NOTE / JUDGE / DISAGREE / LESSON / REFLECT / QUEST_* / ACTIVITY …
--   slot      ：只对 BLOCK 有意义（PERSONA / TASK / PROJECT），其它留空
--   active    ：只对 BLOCK 有意义——每个 slot 只有一条 active，被改写的标 false + superseded_by
--   其它类型的记录一律 active=true、只追加、永不修改。
--
-- ⚠️ 这一步**不动老表**（agent_self_block / agent_self_event / agent_reflection 原样保留、只读），
-- 所以回滚 = 切回旧镜像，数据都还在。确认稳定后再用一条迁移删老表。
CREATE TABLE IF NOT EXISTS agent_self_note (
    id            BIGSERIAL    PRIMARY KEY,
    kind          VARCHAR(32)  NOT NULL DEFAULT 'NOTE',
    slot          VARCHAR(32)  NOT NULL DEFAULT '',
    content       TEXT         NOT NULL DEFAULT '',
    evidence      VARCHAR(200) NOT NULL DEFAULT '',
    topic         VARCHAR(128) NOT NULL DEFAULT '',
    importance    INTEGER      NOT NULL DEFAULT 0,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    superseded_by BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_agent_note_kind ON agent_self_note (kind, id DESC);
CREATE INDEX IF NOT EXISTS idx_agent_note_slot ON agent_self_note (slot, active);
CREATE INDEX IF NOT EXISTS idx_agent_note_created ON agent_self_note (created_at DESC);

COMMENT ON TABLE agent_self_note IS '它自己的话（块 + 事件 + 反思合并）：块=slot+active，其它=只追加的流水';

-- ── 搬数据（可重复执行：靠"这条已经搬过没有"判断，不会翻倍）──

-- ① 块 → BLOCK（每个槽位一条 active）
INSERT INTO agent_self_note (kind, slot, content, evidence, topic, importance, active, superseded_by,
                             created_at, updated_at)
SELECT 'BLOCK', b.block_type, b.value, '', '', 0, TRUE, 0,
       COALESCE(b.updated_at, now()), COALESCE(b.updated_at, now())
FROM agent_self_block b
WHERE NOT EXISTS (
    SELECT 1 FROM agent_self_note n
    WHERE n.kind = 'BLOCK' AND n.slot = b.block_type AND n.active = TRUE
);

-- ② 事件 → 各自 kind（原样保留 kind，QUEST_* / JUDGE / DISAGREE / NOTE / LESSON / ACTIVITY 都在里面）
INSERT INTO agent_self_note (kind, slot, content, evidence, topic, importance, active, superseded_by,
                             created_at, updated_at)
SELECT e.kind, '', e.content, COALESCE(e.evidence, ''), COALESCE(e.topic, ''),
       COALESCE(e.importance, 0), TRUE, 0,
       COALESCE(e.created_at, now()), COALESCE(e.created_at, now())
FROM agent_self_event e
WHERE NOT EXISTS (
    SELECT 1 FROM agent_self_note n
    WHERE n.kind = e.kind AND n.content = e.content AND n.created_at = e.created_at
);

-- ③ 反思 → REFLECT
INSERT INTO agent_self_note (kind, slot, content, evidence, topic, importance, active, superseded_by,
                             created_at, updated_at)
SELECT 'REFLECT', '', COALESCE(r.conclusion, ''), '', '', COALESCE(r.importance, 0), TRUE, 0,
       COALESCE(r.created_at, now()), COALESCE(r.created_at, now())
FROM agent_reflection r
WHERE COALESCE(r.conclusion, '') <> ''
  AND NOT EXISTS (
    SELECT 1 FROM agent_self_note n
    WHERE n.kind = 'REFLECT' AND n.content = r.conclusion
);

