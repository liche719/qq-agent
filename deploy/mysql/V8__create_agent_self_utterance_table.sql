-- 三期领域②的「口」：它想说什么就记下来，**但不打扰机主**（用户选的是先观察）。
-- 应用方式（生产必须先建表再部署，ddl-auto=validate）：
--   docker exec -i wechat-agent-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 \
--     wechat_agent < V8__create_agent_self_utterance_table.sql
--
-- 设计要点（用户 2026-09-15 定的重心）：
--   1. **它自己的事占大头**：这张表记的是"它想跟机主说的话"，不是任务、不是汇报。
--      所以它**不影响**它的作业：记下来只是记下来，机主不会收到任何消息。
--   2. **why 必填**：没有由头的话就是刷屏。"它想说什么"和"它为什么想说"必须一起看，
--      否则面板上只剩一堆句子、看不出它到底在想什么。
--   3. **status 预留**：现在是 PENDING（想说但口没开）；以后要把口开向机主时，
--      直接用 SENT / SUPPRESSED，不需要改表。**不发**这件事本身是可回滚的。
CREATE TABLE IF NOT EXISTS agent_self_utterance (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    content    VARCHAR(1000) NOT NULL,                        -- 它想说的话
    why        VARCHAR(500)  NOT NULL,                        -- 为什么想说（必填）
    quest_id   BIGINT,                                        -- 推进哪个方向时想说的
    status     VARCHAR(16)   NOT NULL DEFAULT 'PENDING',      -- PENDING/SENT/SUPPRESSED
    sent_at    DATETIME,
    evidence   VARCHAR(300),
    created_at DATETIME      NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_self_utterance_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
