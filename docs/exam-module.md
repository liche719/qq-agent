# 考研规划模块（`exam/` 包）

> 2026-09-13 上线并端到端验证。这份文档是模块自己的说明书，`AGENTS.md`（AI 记忆）里只留一条索引，
> 免得那份文件撑爆 64KB 上限。

## 一句话

把「考研」当成一个**有数据、有监督**的模块：备考计划（院校/科目/目标分/阶段）落库 → 每天按计划生成任务 →
早推送计划、晚问完成、周日复盘 → 打卡与完成率由**程序算**（不靠模型记），模型只负责转述和催。
第二轮补上「**执行面**」：章节/轮次进度、错题本（按 1/3/7/15/30 天间隔复习）、阶段里程碑、正计时，
以及**面板里直接编辑计划**（不用只在 QQ 里说）。

## 文件一览

| 文件 | 作用 |
|---|---|
| `exam/ExamPlan.java` / `ExamTask.java` / `ExamCheckin.java` | 三张表的实体（Lombok，字段与列名严格对齐） |
| `exam/ExamProgress.java` / `ExamMistake.java` / `ExamMilestone.java` | 第二轮三张表的实体（章节进度 / 错题 / 里程碑） |
| `exam/*Repository.java` | 六个仓储（只用到派生查询，没有手写 SQL） |
| `exam/ExamService.java` | 计划读写、任务生成/勾选、打卡、统计、结转、各种文案 |
| `exam/ExamTrackService.java` | 进度 / 错题 / 里程碑 / 计时（`StudySession`）与科目分组统计 |
| `exam/ExamPushService.java` | 三条定时推送（早/晚/周），扫描 + "今天已推"标记 |
| `exam/ExamHandler.java` 等 7 个 Handler | 中文指令入口（`考研` / `今日任务` / `打卡` / `考研进度` / `开始学习` / `结束学习` / `错题`） |
| `tool/ExamTool.java` | 约 20 个给大模型用的工具 |
| `controller/AdminExamController.java` | 面板「考研」页的数据接口（含编辑计划表单） |
| `controller/AdminPanelController.java` | 面板页签清单（模块页签由后端描述，前端通用渲染） |
| `deploy/mysql/V3__create_exam_tables.sql` / `V4__create_exam_tracking_tables.sql` | 建表脚本（**MySQL 时代**；2026-09-18 整库迁 pg 后表结构以现库为准，`deploy/postgres/` 下没有单独的 exam 建表脚本。生产是 `validate`，缺表启动即失败） |

## 表结构（`docs/schema.sql` 里也留了索引）

- `exam_plan`：**一个用户一份**（`user_id` 就是主键）。关键列 `exam_date / school / major / stage / daily_minutes /
  subjects / enabled / remark`，以及 `last_morning_push / last_evening_push / last_weekly_push`（保证每天只推一次、重启也不重复）。
  `stage` 取值 `BASIC/INTENSIVE/SPRINT`；`subjects` 是 JSON 数组：
  `[{"name":"数学","targetScore":120,"dailyMinutes":120,"dailyPlan":"强化第3章"}]`。
- `exam_task`：`plan_date`（归属哪天）+ `subject/content/planned_minutes/status/source`，`status` 取 `PENDING/DONE/SKIPPED`。
- `exam_checkin`：一天一条（`user_id + checkin_date` 唯一），存 `minutes` 与打卡那一刻的任务快照。
- `exam_progress`：`user_id + subject + chapter + round` 唯一，存 `percent / note`（章节或整轮的完成百分比）。
- `exam_mistake`：错题本。`subject / chapter / question / answer / reason / status / review_stage / next_review_date`，
  `status` 取 `OPEN/REVIEWING/MASTERED`；`review_stage` 是 `REVIEW_INTERVALS = {1,3,7,15,30}` 的下标。
- `exam_milestone`：阶段里程碑（`title / due_date / subject / done / note`），给「XX 月底过完一轮」这类硬节点用。

建表（**必须先建表再部署**，否则 `ddl-auto=validate` 会让容器起不来）。下面两条是 **MySQL 时代**的命令，
**2026-09-18 整库迁到 PostgreSQL 后已不适用**——`wechat-agent-mysql` 容器 2026-09-20 已退役，
`exam_*` 六张表是随整库迁移过去的（见 `docs/pg-migration.md`），这里留作历史记录：

```bash
docker exec -i wechat-agent-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" wechat_agent < V3__create_exam_tables.sql
docker exec -i wechat-agent-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" wechat_agent < V4__create_exam_tracking_tables.sql
```

> （MySQL 时代）用 mysql CLI 手动插中文数据时**必须加 `--default-character-set=utf8mb4`**，否则中文会被按 latin1 写进去变乱码
> （我用 demo 数据验证时就踩了这条，页面显示成 `æ•°å¦`）。

## 配置（`application.yml` 的 `exam:` 段 + compose 透传）

| 键 | 默认 | 说明 |
|---|---|---|
| `exam.enabled` | `true` | 关掉只是不自动推送，聊天里的指令与工具照常可用 |
| `exam.morning-time` | `07:30` | 早推送（今日计划，缺任务会顺手生成） |
| `exam.evening-time` | `22:00` | 晚推送（完成情况 + 未完成清单 + 打卡引导） |
| `exam.weekly-time` / `exam.weekly-day` | `21:00` / `SUNDAY` | 周复盘（完成率/打卡天数/最弱科目/下周重点） |
| `exam.push-scan-interval-ms` | `60000` | 扫描周期（和墨墨推送同一套路） |
| `exam.carry-over` | `true` | 生成今日任务时，把昨天没做完的 `PENDING` 顺延过来（`EXAM_CARRY_OVER` 透传） |

## 聊天入口

**指令**（走 `CommandRegistry`，不过模型）：`考研`（计划 + 今日安排，别名 考研计划/备考计划/考研目标）、
`今日任务`（没有就按计划生成，别名 今天的任务/今天学什么/生成今天的考研任务）、`打卡 [时长]`
（`打卡 150`＝150 分钟、`打卡 3 小时`＝180 分钟）、`考研进度`（别名 复习进度/考研情况）、
`开始学习 [科目]` / `结束学习`（正计时，结束时把时长累加进当天打卡）、
`错题 <内容>`（直接记一条）/ `错题本`（待复习 · 到期清单）。
`/help` 里的清单是写死的，加指令要同步改 `HelpHandler`。

> `ExamHandler` 声明了 `exactOnly() = true`：**「考研 2026-12-20 报考XX大学 计算机…」这种带信息的整句不该被"首词命中"吞掉**，
> 它必须落到大模型、由模型调 `saveExamPlan`。`CommandRegistry` 的首词兜底会跳过这类处理器（见"插件化"一节）。

**自然语言**（模型调工具）：建/改计划、看计划、列任务、加任务、勾选/跳过、打卡、看进度、开关推送，
以及第二轮的：记/改章节进度、记错题（自动排 1/3/7/15/30 天复习）、复习错题、记/勾里程碑、开始与结束计时。

## 科目分组与墨墨联动

- 科目名支持 `@分组` 后缀：写「数学二@数学」时，面板「科目进度」按 `数学` 汇总（没写 `@` 时 `inferGroup` 按科目名兜底）。
- **墨墨联动是自动的**：`ExamService.eveningText()` 里拼了一行 `maimemoLine()` →
  `ExamTrackService.syncMaimemo(userId)` 读 `MaimemoService.refresh()` 的 `progress.finished/total`，
  写成「背单词」这个科目的进度百分比，并把当天那条背单词任务**自动勾完**；没配 token 就静默跳过（不影响其它功能）。
  也就是说**晚推送（22:00）的收尾文案**里会带上这一行，不需要额外指令。
  **注意**：墨墨开放 API 只给"今日完成/总数"，所以同步的是**当天单词进度**，不是考研词汇总量。

## 早/晚推送里的「跟踪提醒」

`trackSummary(userId)` 会把三类欠账拼进**早推送**（`morningText`）：错题今天到期的条数与内容（最多几条）、
进度超期的单元（最多 3 个）、里程碑超期（最多 2 个）。晚推送（`eveningText`）另加「今天还有 N 条错题到期」，
以及墨墨那一行。**这些数字全是程序从库里算的，不经过模型**——模型只负责把它们说成人话。

## 面板页签（描述式面板）

`GET /api/admin/panels` 返回页签清单：`kind=core` 的 8 个由前端手写组件渲染，`kind=descriptor` 的（考研）由后端给
**区块描述**、前端用 `DescriptorPanel.vue` 通用渲染。考研页签现在有 **11 个区块**：1 个 `info` + 4 个 `form` +
4 个 `table`（都带行内动作）+ 1 个 `bars` + 1 个 `actions`。契约（v2）：

```json
{ "kind": "info",  "title": "备考计划", "endpoint": "/api/admin/exam/plan" }

{ "kind": "form",  "title": "编辑计划", "endpoint": "/api/admin/exam/plan",
  "initialEndpoint": "/api/admin/exam/plan/form", "submitLabel": "保存计划", "hint": "…",
  "fields": [ { "key": "examDate", "label": "考试日期", "type": "date", "placeholder": "2027-12-25" },
              { "key": "stage", "label": "当前阶段", "type": "select",
                "options": [ { "value": "BASIC", "label": "基础" } ] } ] }

{ "kind": "table", "title": "今日任务", "endpoint": "/api/admin/exam/tasks?date=today",
  "columns": [ { "key": "subject", "label": "科目" },
               { "key": "content", "label": "内容", "wide": true, "mono": false },
               { "key": "status", "label": "状态",
                 "tag": { "PENDING": {"text":"待完成","tone":"warn"} } } ],
  "rowActions": [ { "label": "✓ 完成", "endpoint": "/api/admin/exam/tasks/status",
                    "body": { "id": "$id", "status": "DONE" } } ] }

{ "kind": "bars",    "title": "各科完成率", "endpoint": "/api/admin/exam/progress/groups", "unit": "%" }
{ "kind": "actions", "title": "操作", "actions": [
    { "label": "生成今日任务", "endpoint": "/api/admin/exam/tasks/generate", "method": "POST", "confirm": "…" } ] }
```

- 响应形状：`info → {"rows":[{"label","value"}]}`、`table → {"rows":[…]}`、`bars → {"items":[{"label","value"}]}`；
  三个 POST（`actions` / `form` / `rowActions`）都返回 `{"message"}`，`accepted:false` 视为失败。
- **`form`**：`initialEndpoint` 返回 `{"values":{字段名:值}}` 用来预填（编辑计划就是靠它把库里那行读进表单）；
  提交时把**字段名到值的对象**整体 POST 到 `endpoint`。字段类型支持 `text / textarea / number / date / select`。
- **`rowActions`**：`body` 里以 `$` 开头的值是**行内取列**（`"$id"`、`"$total"`），提交前由前端替换成该行的真实值。
  这就是「今日任务行内勾选」「进度 +1 / 做完」「错题 ✓记得 / ✗又错」的实现方式。
- `columns` 的 `wide` 让该列占满剩余宽度（前端还认 `mono`＝等宽，考研页签没用到）。

**加一个新模块页签 = 后端加一个 `descriptor` + 自己的接口，不用改前端、不用重新构建前端。**

## 插件化的三个点（这次顺手做的）

1. **工具自动注册**：`tool/AgentToolProvider.java` 标记接口 + `ToolRegistry` 改成注入 `List<AgentToolProvider>`。
   新工具只要实现接口 + `@Component` + 方法标 `@Tool` 就会被注册，不用再改 `ToolRegistry` 的构造器。
   **代价**：忘了 `implements` 的工具会静默不注册，所以启动日志会打印
   `工具注册完成：N 个类 / M 个工具 -> 类名…`，加完工具核一眼。
2. **中文别名跟着处理器走**：`CommandHandler.aliases()` 默认空，`CommandRegistry` 启动时合并（静态字典仍作为兜底）。
   另外 `CommandHandler.exactOnly()` 让某些入口**不参与首词兜底**——「考研」这类词后面经常跟着用户要交代的内容，吞掉整句模型就拿不到信息了。
3. **面板页签后端驱动**：见上一节。

## 测试时踩到并修掉的两个坑

1. **读库的科目要用 JSON 解析器**：`subjects()` 一开始调的是明文解析器（`数学:120:120:…` 用的那个），
   于是库里的 JSON 被当成一个科目名 → 生成的每日任务标题变成 `[{"name"…` 这种乱码，模型看到乱码又反复重试工具，最后走了兜底文案。
   现在 `subjects()` 走 `readSubjects()`（JSON 优先、失败再按明文兜底）。
2. **任务列表必须带真实 id**：列表只给行号时，模型会把 `1、2` 当成 taskId 传进 `updateExamTask`，
   全部"找不到"（用户看到的是"没勾上，抱歉"）。现在列表渲染成 `1. #12 ⬜ 数学 · 强化第3章`，
   并且 `resolveTask()` 在 id 解析不到时会**把传入的数字当成今天的第 N 条**兜一次。

## 验证记录（2026-09-13）

### 第一批（计划 / 任务 / 打卡 / 推送）

- 建表 → 部署 → 启动日志无 `Schema-validation` 报错；`工具注册完成：10 个类 / 37 个工具`（含 `ExamTool`）。
- 面板：`/api/admin/panels` 有 9 个页签、考研页签 5 个区块；`/exam/plan|tasks|trend|checkins` 空状态与
  有数据状态都返回正确形状；`/exam/tasks/generate` 生成 3 项；`/exam/toggle` 真的改了 `enabled`；
  `/exam/push` 真的把一条「🌙 收尾」推到本人 QQ（主动额度账本同步 +1）。
- 聊天（模拟器通道 + 一次性 `sim-exam-verify` 用户）：自然语言建计划 ✓、`今日任务` 生成干净的中文任务 ✓、
  `打卡 90` 记 90 分钟 ✓、`考研进度` 数字与库一致 ✓、`数学那项做完了` 勾选成功并写备注 ✓、`帮助` 列出考研指令 ✓。

### 第二批（进度 / 错题 / 里程碑 / 计时 / 结转 / 表单）

对**本人的真实数据**做的面板接口验证（自建的验证行测完即删，计划行前后逐列比对确认一字未改），30 项断言全 PASS：

- `工具注册完成：10 个类 / 48 个工具`（37 → 48，新增的 11 个都是 `ExamTool` 的跟踪类工具）。
- 计划表单：`/exam/plan/form` 预填与库里一致；把预填值整体 POST 回 `/exam/plan` 后，
  `exam_date/school/major/stage/daily_minutes/subjects/remark/enabled/last_*_push` 逐列比对**完全相同**
  （第一次比对有差异，查明是**新代码给老计划补齐了 `group` 字段**——一次性升级，第二次就稳定了）。
- 行内动作：任务 `✓完成/撤销`、进度 `+1` / `做完`（把 done 写到 total）/ `删除`、错题 `✓记得`（
  `review_stage` 0→1、下次复习日往后推）/ `✗又错`（回到第 0 天）、里程碑 `完成`（写 `done_at`）/ `撤销`，
  每一项都核对了**库里的列真的变了**，不是只看接口返回。
- 结转：往昨天插一条 PENDING → `POST /exam/push {"kind":"morning"}` → 该行 `plan_date` 变成今天、
  备注写成「从 09-12 顺延」，推送文案里也出现「（1 项是昨天没做完、我顺延过来的）」。
- 聊天（模拟器通道 + 一次性 `sim-exam-track` 用户）13 项全 PASS：自然语言建三科计划 ✓、`今日任务` 出 3 条
  干净中文任务 ✓、`开始学习 数学二` → `结束学习` 把 1 分钟写进当天打卡 ✓、`错题 <内容>` 入库并排到次日回收 ✓、
  `错题本` 列得出来 ✓、`考研进度` 带完成率与连续天数 ✓、`帮助` 里能看到新指令 ✓。
- 清理：`WECHAT_CHANNEL_MODE` 还原 `disabled`（`/api/sim/*` 重新 404）、删掉 sim 与 probe 用户的
  `exam_*`/`conversation_memory`/`user_profile` 行、`/tmp` 临时脚本与 `iptables` 无残留；
  本人仍是 1 份计划 / 3 条今日任务 / `last_channel=qq`。
- 墨墨联动：`POST /exam/push {"kind":"evening"}` 生成的真实晚收尾里出现了 `📖 墨墨背单词：0/250`
  （token 走服务器 `.env` 的 `MAIMEMO_API_TOKEN`，当时 `status: OK / 已连接墨墨开放 API`）。
  **未走到的那一半**：用户当天 `finished < total`，所以「墨墨背完了顺手勾掉背单词任务」这个分支没被触发
  （代码在 `finished >= total` 且当天有名字含「单词」的任务时才勾），等哪天他背完 250 个自然验证。

## 还能再雕的（用户说"用起来再雕"）

- 计划模板（按考试日期倒推阶段与每周目标）、周目标 / 月目标；
- 与「定时任务」联动（把某个科目排成独立任务）；
- 进度/错题的趋势图（现在只有完成率柱状图）。

> 第二轮已经做掉的：面板编辑计划（`form` 区块）、任务行内勾选 / 进度推进 / 错题复习（`rowActions`）、
> 科目分组（`@组` + `各科完成率` 柱状图）、计时打卡（`开始学习`/`结束学习`）、
> 任务自动结转（`exam.carry-over`）、错题回收（1/3/7/15/30 天）、章节轮次进度表、阶段里程碑、墨墨联动。
