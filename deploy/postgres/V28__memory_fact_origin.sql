-- 2026-10-24 信任分级：给事实加"来源可信度"，和已有的 source（材料来源：DOC/USER/AUTO）区分开。
--   STATED   他明确说过的（原话级）
--   INFERRED 从对话推断的（可能错）
--   EXTERNAL 从资料/网页看来的（可能过期）
-- 可空是为了兼容存量 45 条：老数据没有 origin，读的时候按 STATED 处理（当时只从用户对话里抽）。
ALTER TABLE memory_fact ADD COLUMN IF NOT EXISTS origin varchar(16);
