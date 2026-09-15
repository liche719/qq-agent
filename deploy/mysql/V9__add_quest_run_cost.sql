-- 给「它自己的时间」加**钱的账**（2026-09-15 用户定：日预算 0.5 元，封顶 ×1.5）。
-- 应用方式（生产必须先建表再部署，ddl-auto=validate）：
--   docker exec -i wechat-agent-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 \
--     wechat_agent < V9__add_quest_run_cost.sql
--
-- 为什么记"钱"而不是只记 tokens：
--   1. 官方定价里**缓存命中的输入便宜 50 倍**（0.02 vs 1.0 元/百万），而我们的作业
--      12 轮里系统提示词每轮都一样 → 命中率很高。只记 prompt_tokens 会把成本高估好几倍。
--   2. 峰谷价差 2 倍，所以要按**调用发生的那一刻**计价，不能事后估。
--   3. 预算是"元"，重启不能丢 → 必须落库（内存计数一重启就等于免费）。
ALTER TABLE agent_quest_run
    ADD COLUMN cost_yuan DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '这次作业实际花了多少元（按 cache 命中/未命中/峰谷精算）',
    ADD COLUMN cache_hit_tokens BIGINT NOT NULL DEFAULT 0 COMMENT '缓存命中的输入 token（计价用）',
    ADD COLUMN cache_miss_tokens BIGINT NOT NULL DEFAULT 0 COMMENT '缓存未命中的输入 token（计价用）',
    ADD COLUMN extended TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否是"没落产出"后的那一次续期',
    ADD COLUMN budget_yuan DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '这次作业被给的预算（元）';
