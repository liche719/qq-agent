-- 考研规划模块第二批：复习进度、错题回收、阶段里程碑。
-- 同样：生产是 ddl-auto=validate，实体与这里必须严格对齐；建表要先于部署。
-- 应用方式：docker exec -i wechat-agent-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 wechat_agent < V4__create_exam_tracking_tables.sql

-- 章节/轮次进度：一条 = 一个可度量的复习单元（高数第三章、408 数据结构王道、真题 2015 卷…）
CREATE TABLE IF NOT EXISTS exam_progress (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    user_id         VARCHAR(128) NOT NULL,
    subject         VARCHAR(60),            -- 科目（自由文本，如「数据结构」）
    subject_group   VARCHAR(60),            -- 归组（408 / 数学二 / 英语二 / 政治…），面板按它聚合
    phase           VARCHAR(16),            -- BASIC/INTENSIVE/SPRINT/PAST_PAPER（基础/强化/冲刺/真题）
    title           VARCHAR(200) NOT NULL,  -- 单元名（如「王道第三章 栈与队列」）
    total           INT,                    -- 总量（章/题/讲/套）
    done            INT DEFAULT 0,          -- 已完成
    unit            VARCHAR(16),            -- 量词：章/题/讲/套
    due_date        DATE,                   -- 计划完成日（超期未完成要提醒）
    note            VARCHAR(300),
    last_touched_at DATETIME,
    created_at      DATETIME,
    updated_at      DATETIME,
    PRIMARY KEY (id),
    INDEX idx_exam_progress_user (user_id, subject_group),
    INDEX idx_exam_progress_due (user_id, due_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 错题/顽固知识点：间隔复习（1/3/7/15/30 天），答错回退到第一天，走完整轮才算掌握
CREATE TABLE IF NOT EXISTS exam_mistake (
    id               BIGINT NOT NULL AUTO_INCREMENT,
    user_id          VARCHAR(128) NOT NULL,
    subject          VARCHAR(60),
    subject_group    VARCHAR(60),
    title            VARCHAR(300) NOT NULL, -- 题目或知识点摘要
    detail           VARCHAR(1000),         -- 错在哪、正确思路
    source           VARCHAR(60),           -- 来源（660 题/王道/真题 2015…）
    review_stage     INT DEFAULT 0,         -- 已经过了几轮；0 = 刚记下
    next_review_date DATE,
    correct_streak   INT DEFAULT 0,
    status           VARCHAR(16) DEFAULT 'OPEN', -- OPEN/MASTERED/DROPPED
    last_reviewed_at DATETIME,
    created_at       DATETIME,
    updated_at       DATETIME,
    PRIMARY KEY (id),
    INDEX idx_exam_mistake_user (user_id, status, next_review_date),
    INDEX idx_exam_mistake_group (user_id, subject_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 阶段里程碑：带截止日的检查点（基础一轮 2027-03-31、408 一轮 2027-06-30…）
CREATE TABLE IF NOT EXISTS exam_milestone (
    id         BIGINT NOT NULL AUTO_INCREMENT,
    user_id    VARCHAR(128) NOT NULL,
    name       VARCHAR(120) NOT NULL,
    due_date   DATE,
    done_at    DATETIME,
    note       VARCHAR(300),
    created_at DATETIME,
    updated_at DATETIME,
    PRIMARY KEY (id),
    INDEX idx_exam_milestone_user (user_id, due_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
