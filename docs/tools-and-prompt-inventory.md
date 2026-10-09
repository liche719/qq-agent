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
- **不动高频工具**：前 6 个工具占了绝大多数调用，是主力，碰它们风险远大于收益。
