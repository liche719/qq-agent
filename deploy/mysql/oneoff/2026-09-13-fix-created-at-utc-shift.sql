-- 一次性数据修正：把"按 UTC 解释时间 + 写库时 +8"那段历史数据挪回来。
--
-- 背景（见 AGENTS.md 坑 29）：容器 JVM 默认时区是 UTC，2026-09-13 改为 Asia/Shanghai 之后，
-- 之前写入的行整体偏了 +8 小时。判定方法：`select count(*) from <表> where created_at > now()`——
-- 任何"未来时间"的行都是被偏置的证据（提醒的 trigger_at 例外，它本来就可能是未来）。
--
-- 实测只有 conversation_memory 的 id 337~480（98 行）受影响，已于 2026-09-13 在**克隆库上验证**后执行过一次。
-- 这个文件留档是为了换机器/重建库时能复现，**不要无脑重跑**：
--   * 它按 id 区间定位历史数据，重跑会把这 98 行再往前挪 8 小时；
--   * 执行前先 `mysqldump`（DB 优先级的最后一道防线）。
--
-- 用法（在服务器上）：
--   mysqldump --single-transaction wechat_agent conversation_memory > /root/before-utc-fix.sql
--   docker exec -i wechat-agent-mysql mysql -uroot -p"$PW" wechat_agent < 2026-09-13-fix-created-at-utc-shift.sql
--   select count(*) from conversation_memory where created_at > now();   -- 应该回到 0

update conversation_memory
set created_at = date_sub(created_at, interval 8 hour)
where id between 337 and 480;
