-- 2026-10-25 记忆闭环（只做"新增"，不动存量内容）
--
-- ① 事实补证据链：以前提取出来的 sourceMessageIds 被直接丢弃，导致用户说"忘掉这句话"时
--    连不回相关事实，只能靠猜。加一列，存 CSV 形式的消息 id。
ALTER TABLE memory_fact ADD COLUMN IF NOT EXISTS source_message_ids varchar(1000);

-- ② 旧数据补来源类型（31 条空值）：PROFILE 按"他明确说的"，其余按"用户派生"。
--    只填空值，不改已有值。
UPDATE memory
   SET source_type = CASE WHEN kind = 'PROFILE' THEN 'USER_EXPLICIT' ELSE 'USER_DERIVED' END
 WHERE source_type IS NULL OR source_type = '';

-- ③ 【故意不做】批量给 TASK 补 30 天 TTL。
--    2026-10-25 dry-run 查出来：会命中 65 条 kind=TASK 的历史记忆，其中 42 条建库已超过 30 天，
--    一执行就立刻过期；抽样看全是「用户希望助手说话简洁」「女朋友的专业」「教务系统域名」这类长期事实——
--    它们只是当年被错归到 TASK，并不是临时任务。批量补 TTL 等于删掉这些记忆。
--    正确做法：默认有效期只作用于**以后新提取**的 TASK（代码侧 memory.task-default-ttl-days，默认 180 天），
--    存量这 65 条原样保留。
