# 代码整理清单（2026-10-27）

> 用户原话：「不止这几个类吧，我觉得还有很多冗余和乱的类」。
> 这份清单把"乱在哪、按什么顺序拆、每步怎么验"写死，避免每次重新盘点。

## 现状盘点（2026-10-27 实测）

### 1. 超大文件（>600 行）

```
（2026-10-27 13:17 最新：AgentLoop 已在三步拆分后从 1523 → 1385 行）
2036  channel/qq/QqChannel.java          ← 最乱：收发、面板、进度、额度、分页、引用解析全在一起
1477  agent/AgentLoop.java               ← 对话循环 + 计划 + 反思 + 工具编排（已拆出两块）
1331  memory/MemoryExtractor.java        ← 提示词拼装 + 解析 + 落库判定
 881  bilibili/BilibiliClient.java       ← HTTP 客户端 + 签名 + 登录态
 861  backup/MemoryBackupJob.java        ← 备份/恢复/媒体去重
 856  memory/MemoryService.java
 848  memory/MemoryFactService.java
 824  agent/AgentOrchestrator.java       ← 入站编排 + 回复流水线（气泡/语音/来源）
 663  controller/AdminSelfController.java
 607  self/SelfReflectionService.java
 589  reminder/ReminderService.java
 565  schedule/ScheduledTaskService.java
```

### 2. 重复的小工具方法（最典型的"冗余"）

| 方法形态 | 出现在多少个文件 |
|---|---|
| `private static String clip(...)` | **24** |
| `DateTimeFormatter` 自建格式化 | **21** |
| `truncate(` / `safe(` | 6 / 2 |

这些各写一份，裁剪长度、省略号、null 行为**各不相同**，属于典型的技术债。

### 3. 职责混杂的信号

- `QqChannel`：HTTP 发送、Token 刷新、进度会话、被动额度、指令面板、快捷菜单、卡片解析、引用解析、分页——**至少 6 个职责**。
- `AgentLoop`：对话循环、计划触发、反思触发、工具编排、进度播报、延迟埋点、预算——**7 个职责**。
- `AgentOrchestrator`：入站合并、命令路由、记忆加载、回复气泡、语音排序、来源附注。

## 拆分顺序（每步一个提交，一步一验）

| # | 目标 | 从哪拆到哪 | 状态 |
|---|---|---|---|
| 1 | 工具执行编排 | `AgentLoop` → `ToolRoundExecutor` | ✅ `14b69c2` |
| 2 | 进度播报 | `AgentLoop` → `ProgressNarrator` | ✅ `e9c10db` |
| 3 | 计划与反思注入 | `AgentLoop` → `PlanCoordinator`（状态：planned/planText/reflectionIds） | ✅ |
| 4 | 延迟与埋点 | `AgentLoop` → `TurnMetrics`（首字/总时长/每跳 tokens） | ✅ |
| 5 | 进度会话状态 | `QqChannel` → `QqProgressSessions`（会话/12 行上限/文本规则；HTTP 仍留 channel） | ✅ |
| 5b | 进度帧发送 | `QqChannel` → `QqProgressFrames`（body/msg_seq/index/首次取 stream_msg_id；降级仍留 channel） | ✅ |
| 6 | 发送与序号 | `QqChannel` → `QqMessageSender`（msg_seq、长消息拆分、撤回、主动/被动） | 🟡 已完成纯逻辑部分（`QqTexts` 判据/切分、`QqNumbers` 边界）；**发送动作未搬** |
| 7 | 指令面板 | `QqChannel` → `QqPanelConfigurator`（指令面板 + 快捷菜单） | ⬜ |
| 8 | 回复流水线 | `AgentOrchestrator` → `ReplyComposer`（气泡拆分 + 语音时序 + 来源附注） | ⬜ |
| 9 | 文本工具合并 | 24 处 `clip` 收敛到 `Texts.clip`（统一省略号与 null 语义） | ⬜ |
| 10 | 备份拆分 | `MemoryBackupJob` → `BackupWriter` / `BackupRestore` | ⬜ |

> 第 9 项要**先统一语义再替换**：现在的 `clip` 有的是"截断加 …"，有的不加，直接换会改变输出。
> 做法：先加 `Texts.clip(text, max)`（与主流行为一致：超长加 …），逐文件替换 + 跑评估。

## 每一步的固定纪律（不遵守就别合）

1. **只搬代码不改逻辑**；必须改行为时单独一个提交并写清原因；
2. `mvn -q -DskipTests package` 必须 0；
3. 本地起 pg+redis+app，**simulator 真跑一轮带工具的对话**（验证工具执行/进度/回复）；
4. 一步一个提交，message 写清"从哪搬到哪"；
5. 部署 → 6 项验证（镜像 tag / site 200 / Started / 0 ERROR / 64 工具 / 统计接口）→ **`docker tag <新sha> qq-agent:stable`**；
6. 出事：`qq-agent-rollback stable`（150 秒内回来），再从容 `git revert`。

## 明确不做的

- **不重写记忆三层**（`memory` / `memory_fact` / `conversation_memory` 的边界是设计，不是乱）；
- **不合并 `MemoryService` / `MemoryFactService`**：一个管中长期记忆、一个管事实层，合并会让"取代链"和"TTL"互相污染；
- **不动 `self/` 包**（48 个类看着多，但都是小类、职责单一，属于正常分层）。
