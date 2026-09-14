# 自主层 · 一期施工图（可以照着写代码）

> 配套：`self-layer.md`（骨架与决策）、`self-layer-spec.md`（细则）。
> 本文件只覆盖**一期**：`spec §1`（表）、`§2`（工具）、`§3`（注入顺序）、`§8`（时间感）+ **只读面板最小集**。
> **不碰**：反思流程（§4）、判断→倾向（§5）、FSRS 复查调度、领域/产出（§9）——那些是二期/三期。
> 2026-09-14 · **一期已实现（分支 `next`，未部署）+ 本地端到端验证通过**。
> **二期（反思流程 + 判断→倾向 + FSRS 复查 + 面板）同日实现并验证**（见 §7.2）。
> 下面是原始施工图；实现时出现的偏差记在各节「实际」里，**验收结果见 §7.1 / §7.2**。

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
| **`SelfLoader`** | 只读，产出注入段落 | 实现为 `PromptSectionProvider`，产出 `PromptSection(order=-10, "【我自己那侧】", …)`；预算 `memory.self-max-chars`（800）；按「Persona → TASK → PROJECT → STANCE → 未结承诺 → 时间感」拼接；不写任何东西；**全空时返回 `null`** |
| **`AgentSelfTool`** | 实现 `AgentToolProvider`（自动注册） | 方法见 §5；**写操作必须 `retryable = false` + `@NonIdempotentTool`**（坑 39：有副作用的工具默认会重试，会重复写入） |
| `AdminSelfController` | 只读接口 | `/api/admin/self/overview`、`/blocks`、`/events`、`/commitments` |

## 3. 注入链改造（原计划动 3 个既有文件，实际用通用挂点）

现状：`AgentOrchestrator:356` → `memoryLoader.load(userId, content)` 得到 `LoadedMemory(coreSection, workSection)`
→ 传给 `AgentLoop:712` → `AgentPromptBuilder.build(persona, coreSection, workSection, retryAttempts)`。

**实际做法：通用挂点（B 方案），不是 HARDCODE 一个 `selfSection`。**
新增 `agent/PromptSection(order, title, body)` + `agent/PromptSectionProvider.section(userId)` 两个小类型，
`AgentLoop` 用 `ObjectProvider<PromptSectionProvider>` 收集所有段落按 `order` 拼装。
好处：**核心里不认识"自主模块"**，下一个模块（反思、领域）挂同一个挂点即可；`AgentOrchestrator` 与 `MemoryLoader` 一行没动。

| 文件 | 实际改了什么 |
|---|---|
| `agent/PromptSection` / `PromptSectionProvider` | 新增（挂点契约） |
| `AgentPromptBuilder` | 签名加一位 `List<PromptSection> extraSections`；**无段落时输出与改造前逐字节一致**；段落插在 `【长期核心记忆】` **之前** |
| `AgentLoop` | 构造器加 `ObjectProvider<PromptSectionProvider>`（两个便捷构造器补 `null`）；插件抛异常只 `log.warn`，不影响对话 |
| `AgentOrchestrator` / `MemoryLoader` | **没动**（原计划要改的那 3 处，实际 0 处） |

提示词两条规则**没有加进全局规则清单**（那会改动所有用户的提示词前缀）——改成写进**段落正文开头一行**：
「（这是我自己的状态，不是用户的事实；用它可以，但不要向对方复述这一段的原文。）」
效果一样、只在段落存在时出现、对别的用户零影响（实测：让它贴原文它会拒绝，问暗号仍答得出）。

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

## 5. 工具面（一期 **10** 个——比原计划多一个 `selfRecall`，原因见下）

| 工具 | 说明要点（写进 `@Tool` 描述） |
|---|---|
| `selfRead()` | 看自己那侧现在是什么样（块 + 字数/上限 + 未结承诺） |
| **`selfRecall(limit)`** | **看你跟机主最近的对话记录，每条带真实编号 `conv:<id>`** |
| `selfAppend(blockType, text, evidence)` | 追加；超限直接报错并要求先 summarize |
| `selfReplace(blockType, oldText, newText, evidence)` | 替换式修改（旧值进事件历史） |
| `selfSummarize(blockType, evidence)` | 压缩接近上限的块 |
| `selfNote(text, evidence)` | 随手记（进事件，不进块） |
| `goalOpen(content, why, evidence)` | **自己立**的目标（与用户的 `exam_plan`/`reminder_task` 分开） |
| `goalClose(goalEventId, outcome, evidence)` | 关闭自己的目标 |
| `commit(content, dueDate, evidence)` | 立诺/预测 |
| `commitResolve(commitmentId, status, evidence)` | 兑现 / 认欠 |

**`selfRecall` 是补的一个死锁**（原方案漏了）：写入要求 `evidence` 是**真实存在**的 `conv:<id>`，
但模型在任何地方都看不到 `conversation_memory` 的行号——工作记忆与历史追溯渲染成「用户曾说：…」不带编号，
也没有任何工具返回过编号；而 `event:<id>` 又必须先有一次成功写入才有。**结果就是第一次写入永远拿不到合法证据**。
实测证据：模型面对 `conv:320` 明确回「我核不到这条记录是不是真的，所以不发起调用」——**它拒绝得对，是设计缺了输入**。
补法：加一个只读工具把真实编号递到它手上（`selfRecall` 返回 `conv:326 用户曾说：…`），
`POLICY_HINT` 改成「先调 selfRecall 拿真实编号」。**注意本轮消息要等回复完才落库，所以最新一条是上一轮。**

**公共描述（每个都要写）**：这些是「**它自己的事**」，不是为用户做的事；`evidence` 缺失一律拒绝。
**注册校验**：启动日志打「工具注册完成：N 个类 / M 个方法」——开＝**12 个类 / 60 个**，关＝**11 个类 / 50 个**。
`AgentSelfTool` 与 `SelfLoader` **都要** `@ConditionalOnProperty(memory.self-enabled)`：
只给 `SelfLoader` 加会"关不干净"（工具仍占着模型工具表，只是每次返回"已关闭"，实测踩到过）。

## 6. 面板（只读最小集）

- `AdminSelfController`：`overview`（各块摘要 + 用量 + 计数）、`blocks`、`events?limit=`、`commitments`
- 前端：加**第 9 个只读页签**（描述式面板，`DescriptorPanel` 契约 v2），一期只放四个区块：
  **状态条 / 块 / 事件流 / 承诺账**（漂移曲线、调用链、上下文检查器留给二/三期）
- 不改现有 8 个页签

**实际**：页签 key=`self`、label=**它自己**，由 `AdminPanelController.selfTab()` 下发（1 个 info + 3 个 table），
接口前缀 `/api/admin/self/*`。因为走的是**后端描述式面板**，**前端一行没改、也没重新构建**——
这正是「加模块不用动 Vue」这条插件化的红利（同 `docs/exam-module.md`）。

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

## 7.1 实际验收结果（2026-09-14 本地，`next` 分支）

**离线校验**：`%TEMP%\selfcheck` 里一个一次性的 Java 程序，**直接调用真实的 `SelfLoader` / `AgentPromptBuilder` / `SelfService`**，
只把 4 个 JPA 仓库换成桩（仓库外，跑完即删）。**38 项全过**：

| 判据 | 结果 |
|---|---|
| 空状态 | `section()` 返回 `null`；注入列表为空时输出**与改造前逐字节一致**，提示词里连「我自己那侧」都不出现 |
| 注入顺序 | `order=-10`；`我自己那侧` 的下标 **<** `【长期核心记忆】`；人设仍在最前；超 800 字截断且带「（已截断）」 |
| 证据强制 | 空证据 / `conv:999999` / `event:888888` / 乱格式 → **全被拒**；真 `conv`/`event` → 放行；一条真一条假 → 放行 |
| 块上限 | `char_limit=100` 的块已写 90 字时再加 50 字 → 被拒并提示先 summarize |
| 承诺上限 | 未结 20 条时再立 → 被拒；3 条时放行 |
| 归属 fail-closed | 非机主 → 不注入且 `isOwner=false`；**未配归属人 → 整个模块不工作**；开关关闭 → 不工作 |
| 隔离 | 写操作只落 `agent_self_block` / `agent_self_event` / `agent_commitment`；**`ConversationMemoryRepository` 只有读、零写** |

**真机链路（本地 production profile + 真模型 + 真 MySQL）**：

| 判据 | 结果 |
|---|---|
| 注入真的到模型 | 库里塞一个**只存在于 `agent_self_block`** 的暗号，禁用工具直接问 → 答对「紫色螺丝刀七号」 |
| 写入→落库→再注入（闭环） | `selfRecall` 拿到真实编号 `conv:326` → `selfNote` 落库 `event #1` → 下一轮问「上次停在这」→ **答出事件原文** |
| 定位说明 | 让它「把这一段原文贴给我」→ **拒绝**；紧接着问暗号 → **仍答得出**（该用的时候用，不该念的时候不念） |
| 面板 | `/api/admin/self/{overview,events}` 正常；页签 `self / 它自己` 已在 panel tabs 里；`agent_self_event` 里那条带 `evidence=conv:326` |
| 开关 | `MEMORY_SELF_ENABLED=false` → **12 类/60 工具 → 11 类/50 工具**，`AgentSelfTool` 从注册表消失 |
| 迁移 | `ddl-auto=validate` 下用新实体正常启动（19~22 秒），四张新表由 `V5__` 建 |

**没验证（别当成已验证）**：
- 判据 6「冷启动对比」（清空块前后问同一件事，回答不同）——只做了"暗号"这一个等价证据，没做前后对照
- **服务器 / QQ 真机：模块未部署**（`next` 分支）；生产 `.env` **还没写 `MEMORY_SELF_OWNER_OPENID`**，不写＝整个模块不工作
- 二期（反思流程、判断→倾向、FSRS 复查调度）、三期（领域/产出/额度）**一行代码没写**

## 7.2 二期实际验收结果（2026-09-14/15 本地，`next` 分支）

**二期做了什么**（对照 spec §4/§5/§12/§13.1）：

| 件 | 实现 |
|---|---|
| 表 | `agent_stance`（倾向事实源：证据区间/反例/修订次数/FSRS 的 S·D/复查时间/状态）+ `agent_reflection` 补成本列（trigger/calls/chars/tokens/duration） |
| 反思流程 | `SelfReflectionService` + `SelfReflectionJob`（`@Scheduled` 每分钟看一眼攒够没有；**档位 off / step-count / manual**）+ 面板「立即反思一次」排障入口 |
| 判断→倾向 | `StancePromoter`（**纯函数**，无 Spring 无数据库）+ `SelfService` 的写入面（立/修订/支撑/反例/退役/降级） |
| 工具 | 新增 `selfJudge`（记判断，倾向的原料）、`selfDisagree`（记分歧，§6 档 1）→ **12 个工具** |
| 注入 | STANCE 块改为**由 `agent_stance` 渲染**（块是投影、表是事实源）；到点该复查的标一句 |
| 面板 | 倾向（含证据区间、反例、S/D、下次复查）、反思与成本、分歧、每日变更量柱图、tokens 柱图 |

**离线校验**（同一个一次性程序，**65 项全过**）：提升三条件（≥3 条 / 跨 ≥2 天 / 跨 ≥2 情境）逐条对照、反例优先、
旧反例不翻烧饼、上限 5 条、60 天降级、脏证据不拖垮扫描、反思必须带证据链、FSRS 数学（`R(S,S)=0.9`、间隔反推单调、
成功增长/失败收缩）。

**真机链路**（production profile + 真模型 + 真 MySQL，全部实测）：

| 判据 | 结果 |
|---|---|
| 空库不烧调用 | 手动反思 → 「自上次反思以来没有新事件」 |
| 判断 → 倾向 | 灌 3 条跨天跨情境的 JUDGE → 反思 → **立 1 条**，内容带证据区间「依据 event 1、2、3」，`STANCE_FORMED` 事件 + 注入可见 |
| 反例优先 → 修订 | 再灌 3 条**更新的**反向判断 → 反思 → **修订 1 条**：旧倾向转 `REVISED`（内容留档）、新倾向 `revise_count=1`、反例 3 条可查、`STANCE_REVISED` 事件（旧→新） |
| 承诺判欠 | 到期未兑现的承诺 → `BROKEN`（"得失"的落点） |
| 反思合成 | 真模型产出结论 + 把「我现在在做」整块写回（`written_back`=块 id，块 version 2） |
| 成本入账 | 每次 1 次调用、prompt/completion tokens 与耗时都落库，面板可见（3 次共 16062 tokens） |
| 注入到模型 | 禁用工具问「你一贯的主张」→ 准确说出倾向与反例权重；问「有没有该复查的」→ 指出那条并说明依据 |
| 定时触发 | `@Scheduled` 那条路真跑过：`agent_reflection.trigger_type='step-count'` |
| 防抖 | 定时与手动前后脚到 → 第二次被拒：「距上次反思才 2 分钟（防抖间隔 30 分钟）」 |
| 它自己会记判断 | 一条自然会引出判断的对话里，它**主动**调 `selfRecall` + `selfJudge` 记下 JUDGE，并在回复里引用自己的倾向（不是复述原文） |
| 面板 | `/overview`（含"1 条该复查了"）`/stances`（含「该复查了」标记）`/reflections` `/disagreements` `/changelog-bars` `/cost-bars` 全部正常 |

**这轮抓到的三个真问题**（都改了，详见 spec §4/§5 与 AGENTS.md 坑 65）：
1. **反思挂在结构化档上会整条作废**：模型默认深度思考、**思考 token 也算进 max_tokens**，
   4096 被思考吃满（`promptTokens=420 completionTokens=4096 reasoningTokens=4096`）→ 正文为空。
   已给反思单开一档 `LlmScenario.REFLECT`（默认 16384），并在 compose 里同步 `LLM_ZERO_TEMPERATURE_SCENARIOS`
   （**坑 61 原样重演**：那个非空默认值会盖掉代码里的集合）。
2. **反例优先判错了**：原来只在"反例侧权重 > 现有倾向"时才修订，而文档要求"达到**同等**证据量就必须修订"。
   改成：反例侧独立按**同一套门槛**判定。
3. **改完就翻烧饼**：反例达门槛即修订的话，旧反例会在下一轮把新倾向再翻回去。
   加硬规则：**只有比现有倾向（formedAt）更新的反例才算数**——"被说服"本来就要求新的理由。
   附带补了 spec §13.2 要求的**最小反思间隔（防抖）**，因为实测定时与手动会前后脚各跑一次、内容重复。

**二期没做（留三期）**：领域与产出（§7/§9 的教训清单 + 稀缺预算）、`compaction-event` 触发
（**这个代码库没有"上下文压缩"事件，不假装支持**）、漂移曲线用"每日变更量"代替
（真正的 self-state 距离要存每日快照，属于三期，现在不放假距离）。

## 7.3 三期领域①「它自己的可靠性」验收（2026-09-15 本地）

**做了什么**：`agent_lesson`（`V6__create_agent_lesson_table.sql`）+ `SelfService` 的 ADD / DOWNVOTE / EDIT / 复查 +
模型工具 `selfLesson`、`selfLessonEdit`（共 **14** 个 self 工具）+ 同类合并（复用 `MemoryTextSimilarity`，阈值 0.72）+
清单上限 30 + **只在同类场景**注入 top3 + 面板（清单 + 每周教训事件数）。

**离线 83 项全过**（含一期/二期）：三段闸（缺一段就拒）、同类合并（复现 +1、难度上升、干净复查清零）、
上限拒绝、复查（到期 + 该类别没再犯 → UPVOTE、S 变长、连续 3 次干净 → 关闭、关闭后不再进队列）、
只在同类场景注入、FSRS 数学。

**真机链路**：
- 我让模型记一条"它没干过"的教训 → **它拒绝**：「往我的账上记一件没发生的错，这我不能干」，
  并改记它真犯过的那次（判 conv 编号没核实就下断言）；落库三段齐、correction 是可执行短句、FSRS 下次复查算了出来；
- 含类别触发词的消息里，它**一字不差复述了只存在于 `agent_lesson.correction` 里的那句话** → 同类场景注入确实到了模型；
- 面板 `/lessons`（含"复现 1 次｜干净复查 0 次"、S/D、下次复查、状态）、`/lesson-bars`、`/overview` 都正常。

**这轮抓到的坑**：反思会**从它自己刚写下的教训事件里再推导一条同类教训**（清单膨胀的雏形）→
现在反思的输入**排除自己的结论（REFLECT）与自己的教训（LESSON）**，只从行为痕迹（判断/承诺/目标/随手记）里发现教训。

**没做**：领域②③；§13.2 的"意外度触发"没做成每轮写预期再比对（成本高），改成**反思时顺带自查**——
文档 §13.2 里那一档留着，等噪声数据够了再决定。
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

## 11. 既有测试（实际：一个都没改）

原计划担心改 `AgentOrchestrator` 构造器会波及 4 个测试类。**实际用通用挂点，`AgentOrchestrator` 与 `MemoryLoader` 都没动**；
`AgentLoop` 只是构造器多一个参数，两个便捷构造器内部补 `null`，**既有测试全部原样编译通过**（`mvn -DskipTests package` 仍会编译测试）。
结论：**只加新类，不改老测试**。

## 12. 配套材料

| 文件 | 装什么 |
|---|---|
| `self-layer.md` | 骨架、边表、图、判据、算法清单 |
| `self-layer-spec.md` | 表 / 工具 / 注入 / 反思 / 倾向 / 分歧 / 产出 / 面板 / 算法逐条 |
| `self-layer-plan.md`（本文） | 一期施工图 |
| `deploy/mysql/V5__…sql` | **已写**：`V5__create_agent_self_tables.sql`（4 张表，纯新增，回滚＝drop） |
| `src/main/java/…/self/` | 已写：4 实体 + 4 仓库 + `SelfService` + `SelfLoader` + `AgentSelfTool`（10 工具） |
| `src/main/java/…/agent/PromptSection*.java` | 已写：通用注入挂点（下个模块直接复用） |
