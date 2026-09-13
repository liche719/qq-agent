-- 考研规划模块（exam/ 包）的三张表。
-- 生产是 ddl-auto=validate，实体和这里必须严格对齐；改完记得同步 docs/schema.sql 里的说明。
-- 应用方式：docker exec -i wechat-agent-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" wechat_agent < V3__create_exam_tables.sql

-- 备考计划：一个用户一份（user_id 就是主键）
CREATE TABLE IF NOT EXISTS exam_plan (
    user_id            VARCHAR(128) NOT NULL,
    exam_date          DATE,                  -- 考试日期（初试）
    school             VARCHAR(120),          -- 报考院校
    major              VARCHAR(120),          -- 报考专业
    stage              VARCHAR(16),           -- BASIC/INTENSIVE/SPRINT（基础/强化/冲刺）
    daily_minutes      INT,                   -- 每天计划学习分钟数
    subjects           VARCHAR(2000),         -- 科目 JSON：[{"name":"数学","targetScore":120,"dailyMinutes":120,"dailyPlan":"强化第3章"}]
    enabled            BIT(1) DEFAULT b'1',   -- 关掉就不再自动推送
    remark             VARCHAR(500),
    last_morning_push  DATE,                  -- 最后一次早推送日期（保证每天只推一次）
    last_evening_push  DATE,
    last_weekly_push   DATE,
    created_at         DATETIME,
    updated_at         DATETIME,
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 每日任务：早上按计划生成，用户或模型也可以随时加/改
CREATE TABLE IF NOT EXISTS exam_task (
    id               BIGINT NOT NULL AUTO_INCREMENT,
    user_id          VARCHAR(128) NOT NULL,
    plan_date        DATE NOT NULL,          -- 归属哪一天
    subject          VARCHAR(60),            -- 科目
    content          VARCHAR(300),           -- 任务内容
    planned_minutes  INT,                    -- 预计用时
    status           VARCHAR(16),            -- PENDING/DONE/SKIPPED
    source           VARCHAR(16),            -- PLAN（按计划生成）/MANUAL（用户或模型加的）
    done_at          DATETIME,
    note             VARCHAR(300),
    sort_order       INT,
    created_at       DATETIME,
    updated_at       DATETIME,
    PRIMARY KEY (id),
    INDEX idx_exam_task_user_date (user_id, plan_date),
    INDEX idx_exam_task_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 打卡：一天一条，用于连续天数与趋势
CREATE TABLE IF NOT EXISTS exam_checkin (
    id            BIGINT NOT NULL AUTO_INCREMENT,
    user_id       VARCHAR(128) NOT NULL,
    checkin_date  DATE NOT NULL,
    minutes       INT,                       -- 当天实际学习分钟数
    note          VARCHAR(500),
    tasks_total   INT,                       -- 打卡时的任务快照
    tasks_done    INT,
    created_at    DATETIME,
    updated_at    DATETIME,
    PRIMARY KEY (id),
    UNIQUE KEY uk_exam_checkin (user_id, checkin_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
