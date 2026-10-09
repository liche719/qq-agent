# 工具集 & 提示词清单（2026-10-09，只读盘点，未动手）

## 0. 数据从哪来

| 来源 | 拿到了什么 |
|---|---|
| `conversation_memory` 的 `system` 行（`tool=xxx phase=result`） | 真实工具调用次数，时间窗 **2026-09-01 ~ 10-09**，分母 **517 条用户消息** |
| 源码里的 `@Tool` 声明计数 | **75 个**声明，其中 **22 个是 self 工具**（生产 `MEMORY_SELF_ENABLED=false`，未注册）→ **注册 53 个** |
| `ToolSetTrimmer` + `exam_plan` 表 | 用户 `exam_plan=1`（有备考计划）→ **考试工具没有被裁剪**，所以"零调用"是真没人用 |

> ⚠️ 注意：`conversation_memory` 里的历史调用含**更早期**的记录（那时 self 模块是开着的），
> 所以下面只统计 **53 个现役工具**的调用。

---

## 1. 工具清单（53 个现役）

### 高频（>40 次）

| 工具 | 次数 | 属于 |
|---|---|---|
| `listStoredMedia` | 154 | 媒体检索 |
| `getCurrentTime` | 150 | 时间 |
| `recallMemoryFacts` | 138 | 事实层 |
| `readStoredMedia` | 58 | 媒体读取 |
| `searchWeb` | 48 | 搜索 |
| `readWebPage` | 48 | 搜索 |

### 中频（10~40 次）

`getMaimemoStudyProgress` 40、`searchConversation` 38、`noteStoredMediaContent` 30、
`saveImportantMedia` 28、`searchVerifiedWeb` 24、`parseReminder` 22、`searchLatestWeb` 20、
`listReminders` 20、`inspectRecentUnstoredMedia` 16、`sendDownloadedFile` 12、
`listExamTasks` 12、`listScheduledTasks` 10

### 长尾（1~8 次，17 个）

`replaceReminder` 8、`recentExtractions` 6、`viewExamPlan` 6、`createScheduledTask` 6、
`setScheduledTaskEnabled` 4、`addExamTask` 4、`examProgress` 4、`cancelReminder` 4、
`saveExamPlan` 4、`getReminderStatus` 4、`runScheduledTaskNow` 2、`inspectStoredMedia` 2、
`setExamPush` 2、`viewExamMilestones` 2、`deleteStoredMedia` 2、`viewExamMistakes` 2、
`viewExamProgress` 2

### **零调用（18 个 = 34%）**

| 分组 | 工具 | 我的判断 |
|---|---|---|
| **考试 ×11** | `generateExamTasks`、`updateExamTask`、`examCheckin`、`saveExamProgress`、`updateExamProgress`、`addExamMistake`、`reviewExamMistake`、`saveExamMilestone`、`completeExamMilestone`、`startExamStudy`、`endExamStudy` | **藏**（按需下发）。旁证：`exam_plan=1`、`exam_task=9`、但 **`exam_checkin=0`** —— 用户建了计划和任务，**从没打过卡**。这 11 个正好是"执行面"（打卡/进度/错题/计时），说明他不用这套 |
| **面试 ×3** | `startInterviewPractice`、`recordInterviewRound`、`endInterviewPractice` | **藏**。整个面试模块 5 周零调用——功能是好的（当时端到端验过），只是用户不用 |
| **下载 ×2** | `findDownloadableLinks`、`downloadWebFile` | **藏**。注意 `sendDownloadedFile` 有 12 次调用，但那些发的是**用户自己发的图片**（走 `saveImportantMedia`），不是下载来的 |
| **升档 ×1** | `thinkDeeper` | **候选删除**。5 周零调用 —— 模型从没主动申请过升档。而且现在面板有「思考强度」滑块，这条路更没必要了 |
| **定时任务 ×1** | `cancelScheduledTask` | **留**。零调用是因为用户还没想删过——它是"能不能收拾自己造的东西"的兜底，不能因为没调用过就删 |

---

## 2. 提示词规则清单（26 条 + 15a，实测整块 **3907 字**）

按主题归类，并标出**关联工具的调用量**——调用量就是"这条规则每轮都在付钱、但用得上吗"的证据。

| 主题 | 规则 | 关联工具（5 周次数） | 判定 |
|---|---|---|---|
| 记忆使用与边界 | 1、2、3、25、26 | `recallMemoryFacts` 138 | 每轮相关，**留** |
| 搜索与核对 | 4、11 | `searchWeb` 48 / `readWebPage` 48 / `searchLatestWeb` 20 / `searchVerifiedWeb` 24 / `getCurrentTime` 150 | 高频，**留**（4 和 11 可合并） |
| 注入防护与授权 | 5、7、9 | — | **安全底线，留** |
| 媒体 | 6、10、12、22 | `listStoredMedia` 154 / `readStoredMedia` 58 / `saveImportantMedia` 28 / `noteStoredMediaContent` 30 | 高频，**留**（4 条可合并成 1~2 条） |
| **下载** | **8** | `findDownloadableLinks` **0** / `downloadWebFile` **0** | **长尾 → 挪按需** |
| 尾注与重试 | 14、15、15a | — | 可合并（15 与 15a 同源） |
| 提醒与定时任务 | 16、17、20 | `parseReminder` 22 / `listReminders` 20 / `listScheduledTasks` 10 / `createScheduledTask` 6 | 低频但真实，**留** |
| **面试** | **18** | `startInterviewPractice` **0** / `recordInterviewRound` **0** | **长尾 → 挪按需**（这条本身就有 220 字，是全文最长之一） |
| 背单词 | 19 | `getMaimemoStudyProgress` 40 | **留** |
| **升档** | **21** | `thinkDeeper` **0** | **长尾 → 随工具一起处理** |
| 风格与提问纪律 | 13、23、24 | — | **留**（23/24 是用户明确要的"说话要短 / 能自己查就别问"） |

### 我看到的三类具体问题

> ⚠️ **上面这张表用的是"盘点当时"的编号。2026-10-09 已经重排过**，映射：
> `15a→16`、`16~20→17~21`、`22~24` 不变、`25+26` 合并为 **25**、`21`（升档）整条删除。
> 现在是连续的 **1~25**，没有 15a、没有空号。

1. **编号乱了**：`15` 后面是 `15a`（没有 15b~15z，也不是并列编号）。
2. **重复表述散在多处**：
   - "先调工具再说话"出现在 4（时间/搜索）、12（媒体指代）、18（面试）、22（读完必记）、26（先查事实）
   - "不要凭印象/不要拿旧值"出现在 4、25、26
   - "工具结果由程序标注/尾注由程序加"出现在 9、14
3. **长尾规则混在每轮里**：规则 8（下载）、18（面试）、21（升档）对应的工具**5 周零调用**，但这三条**每轮都在付钱**——18 更是长达 220 字。

---

## 3. 建议的动作（分三档，**等你点头**）

### 第一档：几乎没有风险（只省钱/省注意力，不改行为）
1. **把 `thinkDeeper` 连同规则 21 一起撤掉**（零调用 + 已有面板滑块替代）
2. **规则 15 与 15a 合并**、**规则 4 与 11 合并**、**规则 25 与 26 合并**——纯文字整理，语义不变
3. **编号重排**成连续的 1..N（顺手把 15a 消化掉）

### 第二档：需要你判断"这个功能还要不要"
4. **面试 3 个工具 + 规则 18** → 如果确定不用，就从注册表里摘掉（代码留着，靠开关关；**不删代码**）
5. **考试"执行面"11 个工具** → 你 `exam_checkin=0` 说明不打卡。可以只留 `saveExamPlan`/`viewExamPlan`/`listExamTasks`/`addExamTask`/`examProgress`，其余按需下发
6. **下载 2 个工具 + 规则 8** → 按需下发

### 第三档：独立的小问题（跟整理无关，但顺手记下）
7. **`inspectRecentUnstoredMedia` 失败率 75%**（8 次里 6 次失败）——像个真 bug，值得单独查
8. **`getCurrentTime` 被调用 150 次**——当前时间**已经写进每轮消息**了，这 150 次是白付的往返。像提示词没写清（规则 4 只说"需要时间时调用"，没说"时间已经给你了"）

---

## 4. 我不打算做的

- **不删代码**：只改"注册/下发/提示词"，功能留在仓库里，靠开关控制。
- **不为了省钱而裁**：工具 schema 已被前缀缓存覆盖（97.6% 命中），裁掉省不到 0.0001 元/轮。
  真正的收益是**首字延迟**（少 5.6k token 输入）和**少选错工具**。
- **不动高频工具**：前 6 个工具占了绝大多数调用，是主力，碰它们风险大于收益。

---

## 5. 执行记录（2026-10-09）：做了哪些、**撤回了哪些**

### 5.1 撤回：考试那 10 个长尾**不删了**

本文 §3 第二档原本建议把考试的"执行面"按需下发或删掉。**动手前查代码，发现两件推翻它的事**：

1. **推送在叫用户去调那些工具**。`ExamService` 里明写着：
   - `📕 错题回收（N 条到期）：… 复习完说「错题 #编号 记得」或「又错了」`
   - `🎯 里程碑超期：…`
   - `📕 错题本今天还有 N 条到期，睡前抽五分钟过一遍`
   删掉 `reviewExamMistake` / `completeExamMilestone`，推送就变成"提醒你去用一个不存在的功能"。
2. **错题本本来就有独立的中文指令层**（`ExamMistakeHandler`：发「错题本」能看、发「错题 内容」能记）。
   所以删掉 LLM 工具**删不掉功能**，只会造成"用户发中文指令能用、但自然语言问模型却没有工具"的
   **能力倒挂**——比不删更糟。

**结论：考试组一个都不动。** 真正该省的（每轮的 schema token）本来就已经在缓存里，省不到 3%。

### 5.2 做了：删 `thinkDeeper` + 拆掉那套裁剪机制

| 改动 | 文件 |
|---|---|
| 删工具 `thinkDeeper`（5 周零调用，面板「思考强度」滑块已替代） | 删 `ThinkingTool.java`、`ThinkingQuota.java` |
| 删提示词规则 21（就是讲 thinkDeeper 的那条，130 字/轮） | `AgentPromptBuilder`（**编号保留空位**，因为 22~26 号在 docs 里被按号引用） |
| 自主体可用工具集里去掉 `ThinkingTool` | `SelfQuestService` |
| **删掉"按关键词裁 18 个考试工具"整套机制** | `ToolSetTrimmer` 重写；`AgentLoop.trimmedSpecifications` 连带简化（少了一次"把最近几轮用户消息收集起来"的无用功） |
| 清掉随之作废的配置键 | `application.yml`、`docker-compose.remote.yml`（`THINKING_DAILY_LIMIT_PER_USER`、`AGENT_TOOL_TRIM_HISTORY_TURNS`） |

**为什么删裁剪机制**（三条，都是实测）：① 关键词表里有「学习/进度/计划/任务/数学/英语」这种日常高频词，
加上"有备考计划就 early return"，**它实际上几乎从不生效**；② 工具 schema 已在前缀缓存里，裁掉省不到单轮成本的 3%；
③ 硬编码关键词一旦误裁，代价是"模型说我没有这个能力"，比多带几个工具严重得多。

### 5.3 留下的死代码（待收尾，没清是因为牵连面大）

`LlmEscalation` / `LlmScenario.DIALOG_DEEP` / `AgentLoop` 里 `effectiveMaxRounds` 与升档超时 /
`agent.deep-*` 三个配置 / 面板「思考强度」里的"对话（模型申请升档）"那一行 —— **触发它们的
`thinkDeeper` 已经没了，所以这一整条链现在不可达**。没顺手删的原因：它横跨 AgentLoop 的轮数与超时逻辑、
LlmScenarioSettings、以及前端滑块，属于独立一次改动。**代码里和 yml 里都留了 `⚠️ 已不可达` 的注释。**

### 5.4 提示词整理：只做了"编号重排 + 一组真合并"

**动手前先纠正了两处我自己的判断**（细读原文才发现）：

- **`15` 和 `15a` 不是同源**：15 讲"工具会自动重试"，15a 讲"调用工具不等于创建任务"——两件不同的事，
  当初只是编号用完了才加个 `a`。所以该做的不是"合并"，而是**把 15a 改成 16、后面顺移**。
- **`4` 和 `11` 也不是同主题**：4 是"该用哪个搜索工具"，11 是"参数依赖外部资料就先验证"。
  硬并会得到一条 400+ 字的规则，而**编号规则越长，每条被遵守的概率越低**——所以**不并**。

**真正做了的**：

1. **编号重排成连续的 1~25**（消掉 `15a` 和 `21` 删除留下的空号）。**只改数字，一个字没动**——
   这类改动可证明不改语义。`22~24` 号没变，所以 docs 里按号引用的地方基本不受影响。
2. **`25`（会变的信息怎么记）+ `26`（会变的信息怎么查）合并成一条**——这是唯一一对真·同主题：
   一个讲"写进事实层"，一个讲"回答前先从事实层读"。合并后每条子句都保留，只是并为一段。
   → 规则总数 **26 → 25**。
3. 修掉 docs 里因删除而失效的引用（`docs/llm-call-modes.md` 里"提示词第 21 条"那条）。

**没做的、也是我要说清的**：这次**没有删任何一条规则、没有砍任何一句话**。
真正能让每轮变便宜的只有"砍子句"，但**每条子句都是当初踩了坑才写上去的**（这个项目的失败模式全是
真金白银换来的），在没有"哪条子句已经过时"的证据之前砍它，是拿行为质量赌一个几百 token 的收益——
收益不到单轮成本的 3%，赌注却是"模型开始越界"。**要再往前走，得先有证据**（比如按子句埋点看哪条从没触发过），
那是另一个课题。


