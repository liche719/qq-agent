-- 每个调用场景的「思考强度」面板覆盖值（2026-09-29）
--
-- 为什么要有这张表：`llm.reasoning-effort` 是**启动时读一次**的配置，改一次要改 .env + 重启容器。
-- 而思考强度是那种"想调一下看看效果、不合适马上调回来"的参数——尤其是记忆提取（high 的思考占
-- 4178 token，单次 0.026 元，是当时最大的单项开销）。放进库之后，面板上每个场景一个滑块，
-- 改完立刻生效、不用重启、也不动仓库里的默认值。
--
-- 语义：**一行 = 一个场景被面板覆盖过**。没有行 = 用 application.yml / 环境变量里的默认值；
-- 面板选「默认」= 删掉这一行（回落到配置），不是存一个 "default" 字符串。
-- 已知取值为 low / medium / high；**不存"不传上游默认"**，因为那等于没有这一行。
--
-- ⚠️ 生产 `ddl-auto: validate`：**先在生产库执行本脚本，再部署镜像**。

create table if not exists llm_scenario_setting (
    scenario         varchar(32)  primary key,
    reasoning_effort varchar(16)  not null,
    updated_at       timestamp(6) not null
);
