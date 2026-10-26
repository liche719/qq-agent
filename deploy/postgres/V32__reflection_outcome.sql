-- 2026-10-27 反思效果度量：这一轮反思之后，任务到底成了没有。
--   写 SUCCESS = 这一轮正常给出了回复；FAILED = 走到兜底（轮次/预算耗尽）。
-- 只加一列，老行为 NULL（表示当时没统计），不参与任何判定。
ALTER TABLE task_reflection ADD COLUMN IF NOT EXISTS turn_outcome varchar(16);
