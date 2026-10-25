-- 2026-10-25 记忆闭环三件事
-- ① 事实补证据链：以前提取出的 sourceMessageIds 被直接丢掉，导致"用户说忘掉"时连不回相关事实，
--    只能靠猜。加一列存 CSV 形式的消息 id。
ALTER TABLE memory_fact ADD COLUMN IF NOT EXISTS source_message_ids varchar(1000);

-- ② TASK 类记忆默认 30 天过期（存量：没有 valid_until 的一律补上，按创建时间算）
UPDATE memory
   SET valid_until = created_at + interval '30 days'
 WHERE kind = 'TASK'
   AND valid_until IS NULL
   AND (status IS NULL OR status = 'ACTIVE');

-- ③ 旧数据补来源类型（PROFILE 只收他明确说的；其余按用户派生）
UPDATE memory
   SET source_type = CASE WHEN kind = 'PROFILE' THEN 'USER_EXPLICIT' ELSE 'USER_DERIVED' END
 WHERE source_type IS NULL OR source_type = '';
