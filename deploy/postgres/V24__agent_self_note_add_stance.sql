-- 给合并后的表补一列：`stance`（2026-10-20）
--
-- V23 建表时漏了它。原 `agent_self_event.stance` 是"倾向提升"的原料：
-- `StancePromoter` 按 `topic + stance` 累计 JUDGE 事件，攒够权重才形成一条"我一贯的看法"。
-- 合并后这一列必须跟过来，否则倾向机制会静默失效（攒不出任何东西，也不报错）。
ALTER TABLE agent_self_note ADD COLUMN IF NOT EXISTS stance VARCHAR(16) NOT NULL DEFAULT '';

COMMENT ON COLUMN agent_self_note.stance IS '立场（A/B/中立）；只对 JUDGE 类有意义，倾向提升按 topic+stance 累计';
