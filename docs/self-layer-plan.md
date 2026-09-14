# 自主层 · 一期施工图（可以照着写代码）

> 配套：`self-layer.md`（骨架与决策）、`self-layer-spec.md`（细则）。
> 本文件只覆盖**一期**：`spec §1`（表）、`§2`（工具）、`§3`（注入顺序）、`§8`（时间感）+ **只读面板最小集**。
> **不碰**：反思流程（§4）、判断→倾向（§5）、FSRS 复查调度、领域/产出（§9）——那些是二期/三期。
> 2026-09-14 · 纸面方案，**未写代码**。

## 0. 一期做完是什么样（可验收的样子）

- 它每次醒来，注入里**先看到自己那侧**（Persona / TASK / PROJECT 块 + 活跃承诺 + 「距上次多久 / 上次停在哪」），
  然后才看到用户那侧
- 它能**自己写自己那侧**（追加/替换/压缩块、立自己的目标、立诺与兑现）；
  **每次写入必须带证据**（引用真实存在的对话/事件 id），没证据写不进去
- 面板能**只读**看到：块、事件流、承诺账、注入用量
- **不做**：反思、倾向提升、产出、对外动作

## 1. 数据层：一张迁移文件

文件 `deploy/mysql/V5__create_agent_self_tables.sql`（**必须先建表再部署**——生产是 `ddl-auto=validate`；
风格照 `V4__create_exam_tracking_tables.sql`：`CREATE TABLE IF NOT EXISTS` + utf8mb4 + 显式索引名）。

| 表 | 关键列 | 索引 |
|---|---|---|
| `agent_self_block` | `id, block_type, label, value, char_limit, description, version, created_at, updated_at` | `uk_block (block_type, label)` |
| `agent_self_event` | `id, kind, topic, stance, content, evidence, importance, created_at` | `idx_kind_time (kind, created_at)`、`idx_topic (topic, stance, created_at)` |
| `agent_commitment` | `id, content, due_at, status, evidence, resolved_at, created_at` | `idx_status_due (status, due_at)` |
| `agent_reflection` | `id, level, input_event_ids, conclusion, importance, written_back, created_at` | `idx_level_time (level, created_at)` |

**四张表一次建全**（哪怕 `agent_reflection` 二期才写）——省掉二期再发一次迁移；
建表只加表、不动任何既有表，**回滚 = drop 四张新表**。

## 2. Java 层（新增，放新包 `com.liche.wechatagent.self`）

新包与 `memory` 平级，**目的是不碰冻结的记忆链路**。

| 类 | 职责 | 关键点 |
|---|---|---|
| `AgentSelfBlock` / `AgentSelfEvent` / `AgentCommitment` / `AgentReflection` + 各 `Repository` | 实体与仓库 | 照现有 JPA 风格，`LocalDateTime` |
| **`SelfService`** | **唯一写入入口** | ① `evidence` 必须能解析成真实存在的 `conversation_memory.id` 或 `agent_self_event.id`，否则抛业务异常 ② 块写入按 `char_limit` 校验（超了要求先 summarize）③ 活跃承诺/倾向条数上限 ④ **事件只追加，不修改不删除** |
| **`SelfLoader`** | 只读，产出注入用的 `selfSection` | 预算 `memory.self-max-chars`；按「Persona → TASK/PROJECT → 活跃承诺（按 due）」拼接；不写任何东西 |
| **`AgentSelfTool`** | 实现 `AgentToolProvider`（自动注册） | 方法见 §5；**写操作必须 `retryable = false` + `@NonIdempotentTool`**（坑 39：有副作用的工具默认会重试，会重复写入） |
| `AdminSelfController` | 只读接口 | `/api/admin/self/overview`、`/blocks`、`/events`、`/commitments` |

## 3. 注入链改造（只动 3 个既有文件，改动很小）

现状：`AgentOrchestrator:356` → `memoryLoader.load(userId, content)` 得到 `LoadedMemory(coreSection, workSection)`
→ 传给 `AgentLoop:712` → `AgentPromptBuilder.build(persona, coreSection, workSection, retryAttempts)`。

| 文件 | 改什么 |
|---|---|
| `AgentOrchestrator`（约 356 行） | 在 `memoryLoader.load(...)` 旁边加 `selfLoader.load(userId)`，把 `selfSection` 一并往下传 |
| `AgentLoop`（约 712 行） | 调 `AgentPromptBuilder.build(persona, selfSection, coreSection, workSection, retry)` |
| `AgentPromptBuilder`（9~12 行） | 签名加一位；**在 `【长期核心记忆】` 之前**插入 `【我自己那侧】` 段落 |
| `MemoryLoader` | **不动** |

提示词同时加两条规则（跟着现有编号往下排）：
- 「【我自己那侧】是**你自己的**状态（你在做什么、你欠什么、你上次停在哪），**不是用户的事实**；先看它，再答用户」
- 「不要向用户复述这一段原文；它只是你的背景」

## 4. 配置与透传（这里有个已知的坑）

`application.yml` 的 `memory:` 段新增（照现有 `${ENV:default}` 写法）：

```yaml
  self-enabled: ${MEMORY_SELF_ENABLED:true}
  self-max-chars: ${MEMORY_SELF_MAX_CHARS:800}
  self-block-char-limit: ${MEMORY_SELF_BLOCK_CHAR_LIMIT:1200}
  self-event-max-rows: ${MEMORY_SELF_EVENT_MAX_ROWS:200}
```

⚠️ **`docker-compose.remote.yml` 的 `environment:` 必须补上这几个键**——现状是**一个 `MEMORY_*` 都没透传**，
线上只能吃 yml 默认值（坑 36：compose 只透传列出来的变量，漏了不会报错）。
服务器 `.env` 先不写（用默认值），要调再加。

## 5. 工具面（一期 9 个）

| 工具 | 说明要点（写进 `@Tool` 描述） |
|---|---|
| `self_read(block_type)` | 读自己某个块 |
| `self_append(block_type, text, evidence)` | 追加；超限直接报错并要求先 summarize |
| `self_replace(block_type, old_text, new_text, evidence)` | 替换式修改（旧值进事件历史） |
| `self_summarize(block_type, evidence)` | 压缩接近上限的块 |
| `self_note(text, evidence)` | 随手记（进事件，不进块） |
| `goal_open(content, why, evidence)` | **自己立**的目标（与用户的 `exam_plan`/`reminder_task` 分开） |
| `goal_close(id, outcome, evidence)` | 关闭自己的目标 |
| `commit(content, due_at, evidence)` | 立诺/预测 |
| `commit_resolve(id, status, evidence)` | 兑现 / 认欠 |

**公共描述（每个都要写）**：这些是「**它自己的事**」，不是为用户做的事；`evidence` 缺失一律拒绝。
**注册校验**：启动日志会打「工具注册完成：N 个类 / M 个方法」——加完核对**方法数 +9**。

## 6. 面板（只读最小集）

- `AdminSelfController`：`overview`（各块摘要 + 用量 + 计数）、`blocks`、`events?limit=`、`commitments`
- 前端：加**第 9 个只读页签**（描述式面板，`DescriptorPanel` 契约 v2），一期只放四个区块：
  **状态条 / 块 / 事件流 / 承诺账**（漂移曲线、调用链、上下文检查器留给二/三期）
- 不改现有 8 个页签

## 7. 验收（逐条可执行）

| # | 验收项 | 怎么验 |
|---|---|---|
| 1 | 空状态诚实 | 库里无块时注入显示「（空）」，模型不会瞎编自己的状态 |
| 2 | 证据强制 | 手工调 `self_append` **不带 evidence** → 被拒（看返回与日志） |
| 3 | 上限生效 | 块写到超 `char_limit` → 追加被拒并提示先 summarize |
| 4 | 注入顺序 | 抓一次真实注入文本（日志/面板）确认「我自己那侧」在「长期核心记忆」**之前** |
| 5 | 隔离 | 写 `agent_self_*` 后查库：`user_*` 三张表行数不变 |
| 6 | 冷启动对比 | 清空块前后问同一件事，回答应不同（主文档 §4 判据） |
| 7 | 开关可用 | `MEMORY_SELF_ENABLED=false` → 不注入、工具不下发（灰度与回退都靠它） |
| 8 | 回滚 | 恢复上一镜像 + `drop` 四张新表即回到今天的状态（新表与旧数据零耦合） |

## 8. 风险

1. **挤占对话预算** → 800 字上限 + 面板显示用量（§12 的"上下文压力"）
2. **模型把"自己那侧"当成用户事实** → 提示词两条规则 + 验收 6
3. **工具被滥用乱写** → evidence 校验 + 条数上限 + 事件只追加
4. **重试导致重复写入** → 写操作 `retryable=false` + `@NonIdempotentTool`（坑 39）

## 9. 施工顺序（每步独立可验证）

1. 迁移文件 + 实体/仓库 → `mvn -DskipTests package` 通过；**本地库先建表**再起服务（validate 通过）
2. `SelfService`（证据校验 + 上限）→ 纯程序，可离线跑
3. `SelfLoader` + 注入链 3 处改造 + 开关 → 本地起服务看**真实注入文本**
4. `AgentSelfTool` 9 个方法 → 核对启动日志工具方法数 +9
5. 面板只读页签 + 接口
6. 端到端：本地 → 合并 `main` → 部署 → **QQ 真机**验证（判据 1 / 4 / 6）

## 10. 明确不做（防范围蔓延）

- 反思流程、倾向提升、FSRS 复查调度、领域与产出、漂移曲线、调用链视图 → **二/三期**
- **不改 `memory` 包任何既有类**（那摊是冻结的）
- 不给它任何"对外动作"能力：发消息仍走现有链路与额度

## 11. 会碰到的既有测试（只改到能编译，不新增）

改 `AgentOrchestrator` 构造器（加 `SelfLoader`）与 `AgentPromptBuilder.build` 签名会波及 4 个既有测试类：

| 测试类 | 碰到什么 |
|---|---|
| `AgentOrchestratorMemoryTest` | mock 了 `MemoryLoader`、构造 `new MemoryLoader.LoadedMemory("（暂无）", "（暂无）")`；构造器加参数后要跟着改 |
| `AgentOrchestratorCommandTest` | 构造 `AgentOrchestrator` 时传了一长串 mock，要补 `SelfLoader` |
| `AgentLoopTest` | 构造 `AgentLoop` 的 mock 列表要补 `SelfLoader` |
| `MemoryLoaderTest` | 不受影响（`MemoryLoader` 不动） |

**只改到能编译、不新增测试**（项目规矩）；但 `mvn -DskipTests package` **仍会编译测试**，所以这步不能跳。

## 12. 配套材料

| 文件 | 装什么 |
|---|---|
| `self-layer.md` | 骨架、边表、图、判据、算法清单 |
| `self-layer-spec.md` | 表 / 工具 / 注入 / 反思 / 倾向 / 分歧 / 产出 / 面板 / 算法逐条 |
| `self-layer-plan.md`（本文） | 一期施工图 |
| `deploy/mysql/V5__…sql` | **待写**：本期唯一要新建的迁移文件 |
