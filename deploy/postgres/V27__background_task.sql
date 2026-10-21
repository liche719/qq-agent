-- 2026-10-21：后台长任务的持久化表。
--
-- 为什么要有这张表：BackgroundTaskTool 原来只把任务扔进内存线程池，
-- 进程一重启就全丢——用户等的那条汇报永远不会来，而且他也不知道为什么。
-- 现在任务先落库，重启后：QUEUED 的重新排队，RUNNING 的标 FAILED 并主动告诉他
-- 「刚才那件事因为重启没做完」。
CREATE TABLE IF NOT EXISTS background_task (
    id            BIGSERIAL PRIMARY KEY,
    user_id       VARCHAR(64)  NOT NULL,
    goal          TEXT         NOT NULL,
    -- QUEUED（已排上）/ RUNNING（在跑）/ DONE（做完）/ FAILED（失败或重启中断）
    status        VARCHAR(16)  NOT NULL DEFAULT 'QUEUED',
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    started_at    TIMESTAMP,
    finished_at   TIMESTAMP,
    -- 汇报的原文（截断到 4000 字，够复盘）
    result        TEXT,
    error         VARCHAR(500)
);

-- 启动时按状态捞，跑起来的都是这两类
CREATE INDEX IF NOT EXISTS idx_background_task_status ON background_task (status, created_at);
-- 按用户查他排过什么
CREATE INDEX IF NOT EXISTS idx_background_task_user ON background_task (user_id, created_at DESC);
