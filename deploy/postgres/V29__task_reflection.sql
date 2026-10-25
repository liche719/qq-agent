-- 2026-10-25 反思记忆：把"任务级失败反思"落库，下次遇到同一堵墙先查历史结论。
-- 单独一张表，不混进 memory / agent_self_note：
--   memory 是"关于用户的事实"，agent_self_note 是"它对自己的描述"，任务反思是第三种东西。
CREATE TABLE IF NOT EXISTS task_reflection (
    id            bigserial PRIMARY KEY,
    user_id       varchar(128) NOT NULL,
    tool          varchar(64)  NOT NULL,
    failure_kind  varchar(16)  NOT NULL,
    failure_type  varchar(32),
    root_cause    varchar(300),
    next_action   varchar(300),
    created_at    timestamp(6) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_reflection_user_time
    ON task_reflection (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_reflection_user_tool
    ON task_reflection (user_id, tool, created_at DESC);
