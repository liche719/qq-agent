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
## 进度快照（2026-10-27 18:05）

| # | 内容 | 状态 | 提交 |
|---|---|---|---|
| 1 | `AgentLoop` → `ToolRoundExecutor` | ✅ | `14b69c2` |
| 2 | `AgentLoop` → `ProgressNarrator` | ✅ | `e9c10db` |
| 3 | `AgentLoop` → `PlanCoordinator` | ✅ | `beb6c41` |
| 4 | `AgentLoop` → `TurnMetrics` | ✅ | `ead9fb9` |
| 5 | `QqChannel` → `QqProgressSessions` | ✅ | `0f72c0e` |
| 5b | `QqChannel` → `QqProgressFrames` | ✅（待真机流量验证） | `fd15efa` |
| 6 | `QqChannel` → `QqMessageSender` | 🟡 纯逻辑已抽出（`QqTexts`/`QqNumbers`），**发送动作未搬** | `cf297ed` |
| 7 | 指令面板 | ⬜ | — |
| 8 | 回复流水线 | ⬜ | — |
| 9 | 24 处 `clip` 收敛 | ⬜ | — |
| 10 | 备份拆分 | ⬜ | — |

**行数变化**：`AgentLoop` 1523 → 1385；`QqChannel` 2036 → 1925。
**测试**：CI 里 9 个测试类（含新增 `ToolRoundExecutorTest`/`PlanCoordinatorTest`/`QqTexts*`/`QqNumbersTest`）。

**剩余步骤的共同难点**：都要给 channel 抽一个"HTTP 网关"接口（token/client/seq/被动优先/主动兜底），
一次搬 200~300 行，而且**本地 simulator 不走真实发送通道**，只能靠部署后观察真实对话。
建议在上下文充足时一次做完，不要在疲劳状态下开工。
### 第 6 步的详细拆法（2026-10-27 实测后补写）

**先说结论：这一步不能像前面几步那样"搬一半"。**

我试过把被动回复窗口的三张表（`lastMsgIds`/`lastRecvAt`/`receivedAtByMessage`）抽成一个
`QqReplyWindow` 类，只改"写入 + 读取"两处调用——**立刻发现这会制造"双份状态"**：

| 调用点 | 如果只改一部分会怎样 |
|---|---|
| `rememberReceivedMessage` 写新类 | 旧表不再更新 |
| `sendPassive` 读新类 | 暂时正常 |
| 缓存淘汰 / `clearCaches` / `replyWindowContains` / 第 1696 行的到达时间查询 | **仍读旧表 → 永远空** |

也就是说：**状态类一旦被两处读写，必须一次替换到底**，否则会出现"记得住但查不到"这种最难查的 bug。
我当场回滚了这次尝试（`git checkout` 还原 + 删掉半成品类），仓库回到干净状态。

**正确的拆法（一次提交完成，别中途停）：**

1. 新建 `QqReplyWindow`，一次提供全套 API：`remember / receivedAt / lastMessageId / lastReceivedAt /
   prune(now, ttl) / evictOverflow(maxConversations, maxMessages) / contains(conversationId) / clear()`；
2. **同一提交内**改掉全部 8 处引用（含缓存淘汰、清空、`containsKey`、第 1696 行的查询）；
3. 删掉三张旧表字段；
4. 编译 + 单测（窗口过期、淘汰、清空）；
5. 部署后**必须发一条真实消息**验证被动/主动判定（simulator 不走这条路径）。

**再往后**：`QqMessageSender` 依赖窗口、配额、错误计数三块状态，
所以顺序应是 `QqReplyWindow`（6a）→ `QqQuota`（6b，主动额度 + apiErrorCount/recordApiError）→
`QqMessageSender`（6c，`sendPassive`/`sendProactive`/`postMessage`）。
### 6c 进展（2026-10-27 20:12）

`QqMessageSender` 已就位，**整条链上真正把字节发给 QQ 的地方只剩一处**：

```
QqChannel.postMessage(...)  →  QqMessageSender.post(...)   ← 唯一 HTTP 出口
                                 ├─ QqMessages.path()      群 /v2/groups/ · 私聊 /v2/users/
                                 ├─ QqMessages.body()      markdown / 纯文本、被动才带 msg_id+msg_seq
                                 └─ QqMessages.targetOpenid()
```

**还剩**：`sendPassive`（窗口判定 → 主动兜底）与 `sendProactive`（配额）的策略搬移，
以及 `handleSendFailure`（结果未知/重试分类）——这三块互相咬合，建议一次做完，别分两次。

验证提示：本地 simulator 不走真实发送通道，所以 6c 只能验"构建 + 启动"；
**被动/主动两条分支要靠真实消息**：窗口内应出现 `[qq] send(passive)`，窗口外应出现 `[qq] send(proactive)`。
### 第 9 步实际结果（2026-10-27 22:10）

**16 份 `clip` → 13 份收敛 + 3 份刻意保留。** 分四批迁移，每批独立提交、独立测试：

| 批 | 语义 | 文件 | 目标方法 |
|---|---|---|---|
| 1 | `substring(0,max-1)+…`（总长=max） | BiliCommentGuard / BilibiliActivity / ProactiveCheckinActivity | `Texts.clip` |
| 1 | `substring(0,max)+…`（总长=max+1） | ImageGenClient / TtsClient / VideoUnderstandService | `Texts.clipWide` |
| 2 | 先压平空白再裁 | AgentEvalService / PlanService / TaskReflectionService | `Texts.clipFlat` |
| 2 | **保留 null** | BackgroundTaskTool | `Texts.clipOrNull` |
| 3 | 先 trim 再裁 | BiliCommentTool | `Texts.clipTrimmed` |
| 3 | null→""、`substring(0,max)+…` | DrawPictureTool / DiscoverToolsTool | `clipWide` / `clipFlat` |

**刻意保留的 3 个**（并写明原因，避免以后有人"顺手统一"）：

- `BilibiliTool`：同样是压平后裁，但**没有 null 检查**（传 null 会 NPE）。迁过去会顺带改行为，按"只搬不改"原则不动。
- `TurnTraceStore`：固定 `MAX_PREVIEW`，是内部跟踪专用，语义独特。
- `LlmHealthState`：固定 160 字、**只替换换行符不压平连续空白**，是刻意的（健康详情要保留缩进感）。

**过程中被测试抓到的真错误**：`clipFlat` 的正则写成 `"\s+"`（少一层转义）→ Java 当普通字符 `s` 匹配 →
压平完全失效。是"多行原文应压成一行"那条用例抓出来的，修复后 6 个用例全绿。
## 交接说明（2026-10-27 22:25，写在第 28 轮收尾前）

**已完成 7 个半步骤**，全部有独立提交、经过构建 + 部署 6 项验证、镜像打成 `stable`：

| 步 | 产物 | 提交 |
|---|---|---|
| 1 | `ToolRoundExecutor`（并行/串行编排、顺序回填） | `14b69c2` |
| 2 | `ProgressNarrator`（进度行、心跳、静默工具） | `e9c10db` |
| 3 | `PlanCoordinator`（计划/反思判据与状态） | `beb6c41` |
| 4 | `TurnMetrics`（每跳 token、首字/总延迟） | `ead9fb9` |
| 5 | `QqProgressSessions`（12 行上限、dead 后仍攒） | `0f72c0e` |
| 5b | `QqProgressFrames`（进度帧 HTTP） | `fd15efa` |
| 6a | `QqReplyWindow`（被动窗口三张表 + 淘汰） | `35d77b5` |
| 6b | `QqMessages`（消息体、群/私聊目标） | `12609b2` |
| 6c(传输) | `QqMessageSender`（唯一 HTTP 出口） | `7b5d9b8` |
| 7(半) | `QqPanels`（面板/菜单载荷） | `3038be1` |
| 9 | `support/Texts`（13/16 个 clip 收敛） | `1eaedfc`→`8d8cbc6` |

**行数**：`QqChannel` 2036 → 1864；`AgentLoop` 1523 → 1385。
**测试**：18 个类，CI 每次部署前必跑，红即阻断。

### 剩余步骤怎么继续（给别人/给未来的自己）

| 步骤 | 做法 | 为什么还没做 |
|---|---|---|
| 6c 策略 | `sendPassive`/`sendProactive`/`handleSendFailure` **一次搬完**（依赖 replyWindow + quota + 错误分类，拆开会出双份状态，第 14 轮实测踩过） | 需真机验证被动/主动分支 |
| 7 剩余 | 面板 GET-查重 → POST/PUT 那段 HTTP | 同上 |
| 8 | `AgentOrchestrator` → `ReplyComposer`（气泡拆分/语音时序/来源附注） | 改的是回复外观，必须真机看 |
| 10 | `MemoryBackupJob` → `BackupWriter`/`BackupRestore` | 涉及备份数据，风险最高，建议单独一轮 |

### 一条硬提醒

**"部署成功"不等于"功能验证"**。这一批里已经发生过一次真实事故：
我加的"整条只有历史媒体标记就跳过"守卫，让用户发的消息**完全没有回复**（`replyChars=0`），
而 `site=200`、64 工具、0 ERROR 全部正常。修法已上线（`6987c7b`）+ 记进 pitfalls 94。
所以剩下的步骤做完后，**必须真发一条消息**，看 `[qq] send(passive)` 出现才算完。
