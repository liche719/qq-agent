-- 归档机制彻底删除（2026-09-18，用户决定）
--
-- 背景：归档做的事是"工作记忆超过阈值时，把最老的一批没期限的记忆压成一条摘要"。
-- 实测它早已休眠（摘要只有 3 条、最后一次 2026-09-13），用户认为现有记忆机制（事实层 + 提取）
-- 不再需要它，要求**彻底删掉**：
--   ① 存量 34 行"已归档"的工作记忆**恢复成活跃**（它们本来就是被压掉的原件）；
--   ② `user_work_memory.archived` 这一列删掉（没有产生者了，留着只会误导）；
--   ③ `memory_archive` 表删掉（3 条摘要的原文已另存到服务器 /root/wechat-agent-archive-backup-<时间>.txt，
--      且前一晚的整库 dump 里也有）。
--
-- ⚠️ 必须在**部署新镜像之前**执行：生产是 `ddl-auto: validate`，实体已经不再映射 archived 列，
--    列还在的话不影响 validate，但表还在、列还在就等于没删干净（这次的目标是删干净）。
-- 回滚：`alter table user_work_memory add column archived boolean not null default false;`
--      （记忆行本身一条都没删，只是 archived 标记没了）

update user_work_memory set archived = false where archived = true;

alter table user_work_memory drop column if exists archived;

drop table if exists memory_archive;
