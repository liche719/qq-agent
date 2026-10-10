# 工具集 & 提示词清单（2026-10-09 盘点 + 当天执行记录）

> **这是一份带时间线的记录，不是"当前状态说明书"。读之前先看这张表：**
>
> | 时间 | 内容 | 那时的规模 |
> |---|---|---|
> | **10-09 动手前** | §0~§4：只读盘点（工具调用次数、提示词 26 条对照、分档建议） | **53 个工具 / 26 条规则** |
> | **10-09 动手后** | §5：做了什么、**撤回了什么**、为什么 | **51 个工具 / 25 条规则**（编号连续 1~25） |
> | **10-09 晚** | §6：按工具的**失败率**实测 + 三个修复（A1 不重试确定性失败 / A2 追问不再叫失败 / A3 媒体存量） | 51 / 25 |
>
> **想知道"现在是什么样"，看 `AGENTS.md` §1（规模）与 §6（文档索引）**；
> 本文档回答的是「当时为什么这么决定、数据长什么样」。下面 §0~§4 的数字**都是动手前的快照**，别当成现状。

## 0. 数据从哪来

| 来源 | 拿到了什么 |
|---|---|
| `conversation_memory` 的 `system` 行（`tool=xxx phase=result`） | 真实工具调用次数，时间窗 **2026-09-01 ~ 10-09**，分母 **517 条用户消息** |
| 源码里的 `@Tool` 声明计数 | **75 个**声明，其中 **22 个是 self 工具**（生产 `MEMORY_SELF_ENABLED=false`，未注册）→ **注册 53 个** |
| `ToolSetTrimmer` + `exam_plan` 表 | 用户 `exam_plan=1`（有备考计划）→ **考试工具没有被裁剪**，所以"零调用"是真没人用 |

> ⚠️ 注意：`conversation_memory` 里的历史调用含**更早期**的记录（那时 self 模块是开着的），
> 所以下面只统计 **53 个现役工具**的调用。

---

## 1. 工具清单（**动手前**：53 个现役）

> ⚠️ **2026-10-09 更正：下面这一节的绝对次数是重复计数的，按「砍半」读。**
> 2026-10-09 晚用**单一口径**重查（`role='system' AND content LIKE 'tool=%phase=result%'`）得到 **522 条**，
> 而 `phase=call` 也是 **522 条**——两者完全对称，说明当初把 call 与 result **两条都算了一次**。
> 逐一核对：`listReminders` 20→10、`parseReminder` 22→11、`sendDownloadedFile` 12→6、
> `listExamTasks` 12→6、`createScheduledTask` 6→3……**基本都是精确的 2 倍**。
> **排序与「零调用」结论不受影响**（0 的两倍还是 0）。真实数据见下方 §6。

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

## 2. 提示词规则清单（**动手前**：26 条 + 15a，实测整块 **3907 字**）

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

### 5.3 死代码已于当天清掉（2026-10-09，commit `383d09f`）

`LlmEscalation` / `LlmScenario.DIALOG_DEEP` / `AgentLoop` 的 `effectiveMaxRounds` 与升档超时 /
`agent.deep-*` 三个配置 / 面板「思考强度」的"对话（模型申请升档）"那一行 —— 触发它们的 `thinkDeeper`
已经没了，**整条链不可达，当天全部删除**（10 个文件，+16/-93）。

顺带一起清的：

- `AgentLoop` 的"只在问时间时才拼时间"正则（`currentTimePattern`/`requestsCurrentTime`）——实测**一次都没命中**，
  而模型为此白调了 150 次 `getCurrentTime`；现在改成**每轮都拼**。
- `application.yml` / `docker-compose.remote.yml` 里随之作废的键，以及 `LLM_REASONING_EFFORT` 默认串里的 `dialog_deep=low`。
- `web/src/labels.js` 的 `dialog_deep`。

**生产实证**：镜像 `wechat-agent:383d09f`、0 重启、`工具注册完成：11 个类 / 51 个工具`、
近 40 分钟 0 条 ERROR、面板思考强度接口正好 **5 个场景**（dialog/extract/reflect/reminder_parse/schedule_parse）。

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

> 后续：**第 9 条**在 2026-10-09 晚的 A2 里补了第三种标签（`【需要用户补充信息】`），见 §6.5。

### 5.5 下一步的候选（**未做，等判断**）

盘点时只统计了「调用**次数**」，**没有统计失败率**。而工具失败在库里是有标记可判的——
`conversation_memory` 的 `system` 行、`phase=result`，内容以 `【工具执行失败】` / `【工具执行已中断】` 开头，
或 `工具执行抛出异常：<异常类>`（见 `ToolExecutionOutcome` / `AgentLoop.executeTool`）。
当初算出的唯一一条"高失败率"是 `inspectRecentUnstoredMedia` **8 次里 6 次失败（75%）**，
而那正是 2026-10-09 修掉的那个 bug（`pending == null` 时抛 `IllegalStateException` 而不是返回说明）。
**所以很可能还有别的工具在静默失败** —— 一条按工具分组的 SQL 就能查出来，这是目前最有价值的下一步。

提示词侧还剩两条**有数据支持**可砍的长规则（工具 5 周 0 调用，规则却每轮都在付钱）：
规则 **8（下载**，`findDownloadableLinks`/`downloadWebFile` 0 次**）**、规则 **19（面试**，`startInterviewPractice`/`recordInterviewRound` 0 次，本条 220 字是全文最长之一**）**。
注意规则 19 不是冗余——它是坑 27（模型漏调 `recordInterviewRound`）的补丁；砍它等于退保，
所以这是"功能你还用不用"的判断，不是"技术上该不该"。

> **结论（2026-10-09 用户明确：面试和下载这两个功能都要用）**：
> **规则 8 / 19 都保留，一条不砍。提示词瘦身这条线到此结束。**
> "零调用"只说明 5 周里没被用上，不等于不要这个能力——和考试那 10 个工具是同一条教训（§5.1）。
> **以后不要再把规则 8 / 19 或那两个工具组当"可砍候选"重提**：它们每轮付的那点 token
> 已被前缀缓存覆盖（省不到单轮 3%），而砍掉的代价是"用户要用的功能，模型说我没有这个能力"。
> 若哪天真要再瘦身，唯一站得住的依据是**按子句埋点看哪条从没触发过**（新课题），不是调用次数。

---

## 6. 按工具的失败率（2026-10-09 晚实测，**只读 SQL，未改任何东西**）

口径：`conversation_memory` 的 `role='system' AND content LIKE 'tool=%phase=result%'`；
失败判据＝内容含 `【工具执行失败` / `【工具执行已中断` / `工具执行抛出异常`。
时间窗 **2026-09-02 17:14 ~ 2026-10-09 23:17**，result 行 **522**、call 行 **522**。
**总失败率 26/522 = 5.0%**。

| 工具 | 调用 | 失败 | 失败率 |
|---|---|---|---|
| `readWebPage` | 24 | 8 | **33.3%** |
| `inspectRecentUnstoredMedia` | 8 | 6 | **75.0%** |
| `readStoredMedia` | 30 | 5 | 16.7% |
| `searchLatestWeb` | 11 | 3 | 27.3% |
| `parseReminder` | 11 | 2 | 18.2% |
| `searchWeb` | 24 | 1 | 4.2% |
| `replaceReminder` | 4 | 1 | 25.0% |

其余 **40 个工具有调用、0 失败**（`recallMemoryFacts` 83、`listStoredMedia` 78、`getCurrentTime` 75…）。

### 6.1 **结论：26 次"失败"里 14 次（54%）根本不是失败，或不该重试**

逐个看失败原因（不是猜，是库里的原文）：

| 类别 | 次数 | 原文 | 判定 |
|---|---|---|---|
| **需要用户补充信息**，却被标成失败 | 3 | `parseReminder`「我需要确认一下：上午1-2节的具体上课时间？…」、`parseReminder`「我还不知道具体在什么时候提醒你，告诉我个时间？」、`replaceReminder`「我没听清要提醒你什么事，再说一遍？」 | **误标**。这三条正文本来就是**给用户的话术**，却被包成 `【工具执行失败】` |
| **正常说明**，却抛异常走了失败通道 | 6 | `inspectRecentUnstoredMedia`「没有可查看的近期未保存图片或文件」 | **误标**（`pending==null` 抛 `IllegalStateException`）——**2026-10-09 已修**（返回说明字符串） |
| **确定性失败却被自动重试** | 8 | `readWebPage`：HTTP 404 ×2、HTTP 403 ×2、"这个链接不安全或格式不正确"、"不是可读取的网页文本"、"网页暂时无法访问" ×2 | **白重试**。4xx / 参数非法重试必然还失败，却等了一轮再报 |
| **文件真丢了**（历史伤痕） | 5 | `readStoredMedia`「文件记录存在，但磁盘文件已丢失」 | 全在 **2026-09-13 20:07~20:26**，正是坑 38（媒体落在容器可写层、部署即清空）造成的；挂载修好后再没出现 |
| **搜索服务不可用**（历史伤痕） | 4 | `searchWeb`/`searchLatestWeb`「搜索服务暂时不可用」 | 全在 **2026-09-12 15:52~15:53**，正是修 SearXNG 引擎配置（坑 3）那天；之后没再出现 |

### 6.2 为什么"误标"是**用户可见的真 bug**（不只是观感）

提示词**第 9 条**白纸黑字写着：「工具结果会明确标为'工具执行成功'或'工具执行失败'。**只能依据成功结果声称完成**；
失败时如实说明失败阶段和原因」。

于是用户说「上午 1-2 节提醒我一下」→ `parseReminder` 想追问具体时间 → 被标成**失败** → 模型按第 9 条办事，
很可能回「设置提醒失败了」而不是「上午 1-2 节是几点？」。**澄清被降级成了故障。**

### 6.3 为什么 `readWebPage` 的重试是纯浪费

`WebPageTool.readWebPage` 对 HTTP 4xx / 非法链接**抛 `IllegalStateException`**（第 60、64 行），
而 `@ToolExecutionPolicy(SLOW_EXTERNAL)` 既没 `retryable=false` 也不是 `NonIdempotentTool`
→ `ToolInvocationService.invokeWithRetry` **必然重试一次**。确定性的 404 等一轮再 404，
用户白等一个往返，日志还留下误导性的「已自动重试 1 次」。

> **对比**：`MediaMemoryTool.readStoredMedia` 的 catch 里**已经写了注释**「文件不会自己出现…
> 不要被工具框架当成瞬时故障再重试一轮」，但它用的是 `ToolBusinessResult.failure(...)`——
> 这个工厂方法**恰好**是 `retryable=false`，所以意图达成了。**修法是"用什么返回类型"，不是改注释。**

### 6.4 建议的动作（**方案**；执行结果见 §6.5）

| # | 动作 | 证据 | 风险 |
|---|---|---|---|
| **A1** | `readWebPage` 把**确定性失败**（4xx / 非法链接 / 非文本）改成 `ToolBusinessResult.failure(...)` 返回，**保留**超时 / 5xx / 网络异常继续抛异常走重试 | §6.1 第三行 8 次 | 低：只改返回通道，不改抓取逻辑 |
| **A2** | 给「需要用户补充信息」一个**非失败状态**（输出换标签，如 `【需要用户补充信息】`），`ReminderService` 里**属于追问**的那几处 `notCompleted` 走它；提示词第 9 条补一句"标为『需要用户补充信息』时把问题原样转述给用户，不要说失败" | §6.1 第一行 3 次 + 第 9 条 | 中：动的是**工具结果契约文本 + 提示词**，两边必须一起改并真机验一次 |
| **A3** | 查「记录存在但磁盘文件丢失」的媒体还有多少行（**涉及存量数据，先给方案**） | 09-13 之后未再出现 | 待查 |

**已不需要做的**（本次数据确认已随历史修复消失）：`inspectRecentUnstoredMedia` 假失败、
`getCurrentTime` 白调 75 次、搜索不可用、`readStoredMedia` 重试。

### 6.5 执行结果（2026-10-09 晚，commit `9cd3456`，**三个都做了**）

| # | 做了什么 | 落点 |
|---|---|---|
| **A1** | `readWebPage` 新增私有 `UnreadablePageException`：HTTP **4xx**、非网页文本、跳转异常、跳转次数超限、非法/不安全链接一律**返回** `ToolBusinessResult.failure(...)`（`retryable=false`，不再白重试）；**超时 / 连接失败 / DNS / 5xx 仍然抛异常**，继续享受重试。原方法体拆成 `openAndExtract` | `WebPageTool` |
| **A2** | 新增状态 `ToolExecutionStatus.NEEDS_INPUT` + `ToolBusinessResult.needsInput` + `ToolExecutionOutcome.needsInput`（标签 `【需要用户补充信息】`，**不加「原因：」前缀**，正文就是该问用户的话）；`ReminderOperationResult` 加 `needsInput` 标志（**三分而不是二分**）；`ReminderService` **9 处**追问改走它（`validateParsed` 全部 5 处 + 调整/取消的 ID 类 4 处），**真失败仍走 `notCompleted`**；`ReminderTool` 统一走新的 `toToolResult`；提示词**第 9 条**补上第三种标签并写明"那不是故障，把问题转述给用户"；`AgentLoop.appendToolFailureNotice` **跳过** NEEDS_INPUT（追问不该进"工具调用未完成"的留痕） | `ToolExecutionStatus`/`ToolBusinessResult`/`ToolExecutionOutcome`/`ToolInvocationService`/`ReminderOperationResult`/`ReminderService`/`ReminderTool`/`AgentPromptBuilder`/`AgentLoop` |
| **A3** | **查完不用动**：`stored_media` 只有 `ACTIVE 12 / TRASHED 1`，磁盘 13 个文件 13M，逐条核对 **缺文件 0**。09-13 那批丢失记录已不在库里 | — |

**验证状态**：部署 CI 全绿（3m49s，含域名证书校验）、镜像 `wechat-agent:9cd34564294e…`、上一代 `383d09f…` 保留可回滚；
新增状态链路用一次性脚本做了 **12 项断言全过**（状态/标签/`retryable==false`/不吞问题原文）。
**尚未端到端验证的**：在真实对话里 A2 的表现（要一条真消息，例如「上午 1-2 节提醒我一下」，
预期是**反问具体时间**而不是"设置失败"）——这一步需要真人在 QQ 里发一句。

**没做的、也是我要说清的**：这次**没有删任何一条规则、没有砍任何一句话**。
真正能让每轮变便宜的只有"砍子句"，但**每条子句都是当初踩了坑才写上去的**（这个项目的失败模式全是
真金白银换来的），在没有"哪条子句已经过时"的证据之前砍它，是拿行为质量赌一个几百 token 的收益——
收益不到单轮成本的 3%，赌注却是"模型开始越界"。**要再往前走，得先有证据**（比如按子句埋点看哪条从没触发过），
那是另一个课题。

---

## 7. 2026-10-10 的追加：转发视频 + 工具输出瘦身

§0~§6 是 10-09 那轮的事。**10-10 又动了两批**，记在这里（现状以此为准：**52 个工具 / 12 个类 / 26 条规则**）。

### 7.1 工具输出瘦身（有数据支撑）

拉了一次生产真实分布（`conversation_memory` 的 `phase=result` 行，5 周 522 条）：

| 工具 | 次数 | 平均字符 | 占总工具输出 |
|---|---|---|---|
| `listStoredMedia` | 78 | **2359** | **31.0%** |
| `searchWeb` | 24 | 5352 | 21.7% |
| `readWebPage` | 24 | 3887 | 15.7% |
| `searchVerifiedWeb` | 12 | 3880 | 7.9% |

- **单条最大的三条正好是 8049 / 8045 / 8037** —— 卡在 `AGENT_TOOL_MAX_RESULT_CHARS=8000` 上，**也就是每次都在被硬截断**。
- **每轮工具调用数**（528 轮）：0 次 339（64%）、1 次 73、2~4 次 95、5 次以上 21，**最多一轮 48 次**、单轮工具结果**最大 14.9 万字符**。
- dialog 的 prompt：平均 12095 token、最大 22561、缓存命中 82%。

**做了三件（用户选的低风险档）**：

| # | 改了什么 | 依据 |
|---|---|---|
| 1 | `listStoredMedia` 只给「ID + 文件名 + 保存时间 + 摘要前 40 字」，去掉原文件名与更新时间，并写明"要详情调 `inspectStoredMedia`"；**提示词规则 10 同步改** | 它一项占 31%，而列表只是用来"认出是哪个文件" |
| 4 | `ToolInvocationService.clip` 改成**在段落/句子边界截断**并标注"这只是前面一部分、可以再要" | 原来 `substring(0,8000)` 硬切，模型拿到半句话 |
| 6 | `DOCUMENT_MAX_TEXT_CHARS` 60000 → **20000** | 一个 PDF 6 万字进用户消息太夸张 |

**没做的（有意）**：搜索类**不再自动深读 3 篇正文**这一条用户没选——它会降低核对类回答的准确性，尤其是
`searchVerifiedWeb`（它就是为"读原文再下结论"存在的）。要动的话应只动 `searchWeb`/`searchLatestWeb`。
**也不能做**：事后压缩旧消息——那会打掉前缀缓存（命中 0.02 vs 未命中 1.0 元/百万），反而更贵。

### 7.2 转发视频：`ark_data` 与 B站字幕

- 用户转发的 B站视频，**平台渲染进 `content` 的文本里没有链接**，卡片数据在 `ark_data` 里（详见坑 68）。
  新增 `QqArkCard` 解析它，并把封面 `preview` 当图片喂视觉模型。
  **⚠️ 实测（2026-10-10 拿真实卡片）：B站这种 `ark_type=miniapp` 卡片的 `fields` 只有
  `[preview, source, source_logo, title]`，没有 `jump_url`** —— 拿不到 BV，也就**没法自动取字幕**。
  落地做法：卡片没链接时**按标题搜**（`searchWeb` 搜 `site:bilibili.com <标题>`，且要求标题完全一致），
  搜不到就让用户把链接发来——这两条都写进提示词规则 26 了。
- 新增工具 **`readBilibiliVideo`**（`bilibili/` 包）：BV/av/b23 短链 → `/x/web-interface/view` 拿 cid →
  `/x/player/wbi/v2` 拿字幕轨（失败退回 `/x/player/v2`）→ 下载字幕 → `[分:秒] 文本`。
  **取字幕要登录态**（`BILI_SESSDATA`，2026-10-10 已从用户本地那份 `config.json` 搬到服务器并验过
  `isLogin=true`），拿不到就如实说"没字幕"、不编内容。
- 提示词新增**规则 26**（所以规则数 25 → 26）。


