-- 三期领域①：教训清单 —— 「它自己的可靠性」。
-- 应用方式（生产必须先建表再部署，ddl-auto=validate）：
--   docker exec -i wechat-agent-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 \
--     wechat_agent < V6__create_agent_lesson_table.sql
--
-- 设计要点（docs/self-layer-spec.md §9.1/§9.2/§13.4）：
--   1. **三段必须齐**（我做了什么 / 我当时预期 / 实际发生了什么），缺一段不算教训 ——
--      挡的是"写感悟"（"以后要更细心"这种没法执行的句子）。
--   2. ExpeL 的四个操作 ADD / UPVOTE / DOWNVOTE / EDIT；但**投票只能来自后续真实事件**
--      （没再犯 / 又犯了），模型不能自己给自己点赞 —— 否则又是自我暗示。
--   3. S/D + next_review_at 走 FSRS（与倾向共用 com.liche.wechatagent.self.Fsrs 那套数学）：
--      "该复查了"是算出来的，不是拍脑袋定的天数。
--   4. 同类（category + 文本相似度）**合并**而不是新增，并且清单有上限 —— 否则退化成流水账/写作文。
CREATE TABLE IF NOT EXISTS agent_lesson (
    id               BIGINT NOT NULL AUTO_INCREMENT,
    category         VARCHAR(24)  NOT NULL,          -- TIME/COMMITMENT/GUESS/FORMAT/TOOL
    trigger_type     VARCHAR(24)  NOT NULL,          -- SURPRISE/USER_POINTED/PROMISE_BROKEN/SELF_CHECK
    what_i_did       VARCHAR(500) NOT NULL,          -- 我做了什么
    expected_result  VARCHAR(500) NOT NULL,          -- 我当时预期（可执行，不是"我以为会顺利"）
    what_happened    VARCHAR(500) NOT NULL,          -- 实际发生了什么
    correction       VARCHAR(500) NOT NULL,          -- 以后怎么做（可执行短句）
    first_seen_at    DATETIME     NOT NULL,
    last_seen_at     DATETIME     NOT NULL,
    recurrence_count INT          NOT NULL DEFAULT 1,
    clean_reviews    INT          NOT NULL DEFAULT 0, -- 复查时"没再犯"的连续次数（≥3 → closed）
    stability        DOUBLE       NOT NULL DEFAULT 1,
    difficulty       DOUBLE       NOT NULL DEFAULT 5,
    last_review_at   DATETIME,
    next_review_at   DATETIME,
    status           VARCHAR(16)  NOT NULL DEFAULT 'open',   -- open/improving/closed
    evidence         VARCHAR(300),
    created_at       DATETIME     NOT NULL,
    updated_at       DATETIME,
    PRIMARY KEY (id),
    INDEX idx_self_lesson_status (status, next_review_at),
    INDEX idx_self_lesson_category (category, status, last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
