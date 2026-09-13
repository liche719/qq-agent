# LLM 调用档位：按场景决定要不要"深度思考"（2026-09-13）

## 0. 一句话结论

项目用的 `deepseek-v4-flash-vision-exp`（api.deepseek.com）**默认就在思考**——不是"没开深度思考"，而是**一直在开**。
所以这件事的本质不是"给 agent 加思考模式"，而是**"哪些调用应该把思考关掉"**：只要结构化输出的调用，**80%+ 的输出 token 都花在思考上**，关掉后实测快 2~3 倍、token 少 2/3，JSON 照样合法。

**默认关思考的只有两个场景**（都是实测过、且失败代价小的）：

| 场景 | 为什么关得起 |
|---|---|
| `extract`（记忆提取，每轮都跑） | 同一条消息实测开/关结果一致（`episodes/work/core` 数量相同），8194ms/1516tok → 2254ms/493tok |
| `schedule_parse`（自然语言 → cron） | 8 个频率表达 **8/8 cron 完全正确**，且有 `isValidCron` 兜底（真错了只会反问用户） |

**`reminder_parse` 与 `archive` 保持思考**：提醒时间解析错了=用户**漏掉提醒**，而且一天就几条、省下的 token 可以忽略；
归档摘要进的是长期记忆（第一优先级是"记忆不丢失"），质量优先。对话回复（dialog）也保持默认档，这是用户明确选的。

## 1. 实测证据（直连官方接口，2026-09-13）

| 请求 | 结果 |
|---|---|
| 不带任何参数 | **返回 `reasoning_content`**，`completion_tokens=45` 里 **38 是思考** |
| `thinking:{"type":"disabled"}` | 思考消失，`out_tok=6` ✅ |
| `enable_thinking:false` | **被静默忽略**（照样思考）❌ |
| `chat_template_kwargs:{"thinking":false}` | **被静默忽略** ❌ |
| `reasoning_effort:"none"` | 也能关，但在极简问题上出现过只输出 1 token 的退化，**不用** |
| `thinking:{"type":"enabled"}` | 200，正常（本来也默认开） |
| 流式 `stream:true` | SSE 的 delta 字段 = `[role, content, reasoning_content]`；一次实测里思考 57 字、正文只有 7 字 |
| `GET /v1/models` | 只列 `deepseek-flash`、`deepseek-v4-pro`；**在用的 exp 别名不在列表里**，别顺手"规范化" |
| `deepseek-flash` 直连 | **也是默认思考** → 换模型省不掉思考，开关才是杠杆 |

计费口径：**思考 token 计入 `completion_tokens`**（见 `usage.completion_tokens_details.reasoning_tokens`）。

## 2. 生产上的 A/B（同一份代码、同一条消息，只改请求体里的那个字段）

把"关思考"的字段换成一个上游不认的字段（`{"noop":true}`）= 改动前的行为，跑同一条消息的记忆提取：

| | 耗时 | promptTokens | completionTokens | 其中思考 |
|---|---|---|---|---|
| 思考开（改动前） | **8194 ms** | 858 | **1516** | **1251（83%）** |
| 思考关（现在） | **2254 ms** | 831 | **493** | 0 |

→ **输出 token −67%、耗时 −73%**。记忆提取每轮对话都会跑一次，这是纯粹的浪费削减。

## 3. 实现（改了什么）

- `LlmScenario`（枚举 + ThreadLocal）：`DIALOG` / `EXTRACT` / `REMINDER_PARSE` / `SCHEDULE_PARSE` / `ARCHIVE`。
  **没有给任何 Service 加构造器参数**（避开坑 45），只在四个调用点外面包一层 `LlmScenario.run(..., () -> chatModel.chat(...))`。
  没标注时按 `DIALOG` 处理：漏标注只会"保持默认档"，不会误降档。
- `LlmScenarioSettings`：**字段形状放在配置里**（`llm.thinking.disabled-body`），代码不写死；解析失败就当没配（宁可不塞字段）。
- `OpenAiRequestFactory.buildPayload(..., JsonNode extraBody)`：把额外字段并进请求体；两个自研 ChatModel 在调用时按当前场景取。
- 温度：`llm.zero-temperature-scenarios`（默认四个结构化场景）→ 那些场景用 `0.0`，其余用 `llm.temperature`。
  （注意：温度和思考是**两个独立开关**。收窄之后 `reminder_parse`/`archive` 是"思考开 + 温度 0"，这是有意的。）
- 指标：`RuntimeMetrics.recordLlm(...)` 带场景与 token，快照多一段 `/api/admin/metrics/runtime → llmByScenario`
  （calls / averageMs / promptTokens / completionTokens / **reasoningTokens** / reasoningCalls）。
- 日志：每次调用一行 `LLM 调用 scenario=extract ms=2254 temperature=0.0 thinking=off promptTokens=831
  completionTokens=493 reasoningTokens=0`；流式是 `LLM 流式调用 scenario=dialog … 正文=128字 思考=554字`。

配置键（`application.yml` 有内置默认值，留空即用默认；服务器 `.env` 可覆盖，**已加进 compose 白名单**）：

```
LLM_THINKING_DISABLED_SCENARIOS=extract,schedule_parse                          # 关思考的场景（默认就这两个）
LLM_THINKING_ENABLED_SCENARIOS=                                                 # 显式打开的场景（默认空）
LLM_THINKING_DISABLED_BODY=                                                     # 留空 = 用内置 {"thinking":{"type":"disabled"}}
LLM_THINKING_ENABLED_BODY=                                                      # 留空 = 用内置 {"thinking":{"type":"enabled"}}
LLM_ZERO_TEMPERATURE_SCENARIOS=extract,reminder_parse,schedule_parse,archive
```

## 3.5 顺带修掉的一个老漏洞：重复提醒"只给 cron 不给时间"会被反问

这件事是查"关思考有没有副作用"时挖出来的，**和思考模式无关，但比它严重**：

- 模型经常只填 `repeatCron`、把 `triggerAt` 留成 `null`，而 `ReminderService.validateParsed` 要求 `triggerAt` 非空，
  于是**直接反问用户、不建提醒**（"我还不知道具体在什么时候提醒你"）。
- 实测（每例 3 次）：**「每3天提醒我浇一次花」开思考时也 100% 失败**；「每天晚上 11 点提醒我睡觉」开思考时 **1/3 失败**；
  「每天早上七点半提醒我看英语」关思考后 **3/3 失败**（开思考时 3/3 成功）。
- 修法（两处，都不依赖模型的表达习惯）：
  1. **程序侧兜底**：`ReminderParseService` 在 `triggerAt == null` 且 `repeatCron` 合法时，用 `CronExpression` 算出**首次触发时间**
     （时区显式指定，见坑 29）；cron 不合法就保持 null，交给校验去反问。
  2. **提示词补两条**：有重复需求时 `triggerAt` 也必须给下一次时间；cron 里已写明的时刻不算缺失，不要往 `missing` 里写"具体几点"。
- 修复后端到端验证：三个用例在**同一条消息**里分别建出 `0 30 7 * * ?`（明天 07:30）、`0 0 9 */3 * ?`（9/16 09:00），
  而模糊时间（"等会儿"）**仍然反问**、没有瞎猜。回复里还会主动说"你没说具体时间，系统默认排在上午 9:00"。

顺便补上了**以前根本没透传**的 `LLM_TEMPERATURE` / `LLM_TIMEOUT_SECONDS` / `LLM_CONNECT_TIMEOUT_SECONDS`
（坑 36：compose 只传列出来的变量，改了 `.env` 不生效而且不报错）。

## 4. 让模型自己申请"更多预算"：`thinkDeeper`（2026-09-13 做并实测）

**为什么是工具，不是路由器 agent**：升档要影响的是**当前这一轮**，而只有模型看到问题之后才知道难不难。外部路由器要多一次
LLM 调用（QQ 场景首字延迟直接翻倍），判错了还分不清是谁的错。工具形态下模型在已有上下文里申请，**判错最坏只是没升档，
不改变正确性**（"别让模型记得做某事"这类设计在本项目翻过车，见坑 27、39）。

**升档到底给了什么**（都从**下一轮** model round 起生效，当轮立即判断所以不用重跑）：

| 预算 | 默认 | 升档后 |
|---|---|---|
| 思考 | 模型默认（本来就开） | **显式** `thinking:{"type":"enabled"}`（不依赖上游默认） |
| 工具轮数 | `agent.max-tool-rounds`（8） | `agent.deep-tool-rounds`（16） |
| 流式超时 | `agent.stream-timeout-seconds`（120s） | `agent.deep-stream-timeout-seconds`（240s） |
| 指标 | `dialog` | **`dialog_deep`**（单独一档，方便看"升档值不值"） |

**额度**：单轮最多 1 次（第二次直接告诉模型"已经升过了，把问题答完"）；按用户日额度
`THINKING_DAILY_LIMIT_PER_USER`（默认 5，**0 = 不限**），账本在 Redis `llm:think:<yyyy-MM-dd>:<userId>`；
用尽时工具返回"今天升不了档"，模型按当前档位继续——**不改变正确性**。Redis 异常时 **fail-open**（额度是防滥用、不是安全边界）。

**实测（生产，2026-09-13 23:00）**：给一条"三件事排优先级并说明理由，要仔细分析"的消息——

```
工具开始 name=thinkDeeper success=true durationMs=15
LLM 流式调用 scenario=dialog_deep ms=12664 temperature=0.7 thinking=on maxTokens=0 正文=1269字 思考=1770字
Redis 键 llm:think:2026-09-13:sim-escalate = 1
```
- **模型自己会调**（提示词第 21 条要求"确实复杂才调"），没人逼它；
- 「好的，谢谢」这种**没有**触发升档 ✅；
- 代价就是那一轮的 12.7 秒 + 1770 字思考——复杂任务该付的钱。

## 5. 每场景 `max_tokens`（2026-09-13 做）

之前**一个都没设**，极端长思考没有任何上限。现在：结构化场景（extract/parse/archive）给**宽松兜底**
`LLM_MAX_TOKENS_STRUCTURED`（默认 4096）；**对话档默认不设**（`0`），因为**思考 token 也算进 max_tokens**，
给对话加上限有把正常长回复截断的风险。升档档位（`dialog_deep`）默认同样不设——升档是"要更多预算"，不该反而加个盖子。

实测日志：`scenario=extract … thinking=off maxTokens=4096`。

## 7. 面板「模型与搜索」的按场景表格（2026-09-13 做）

`web/src/panels/LlmPanel.vue` 新增一张表，读 `/api/admin/metrics/runtime → llmByScenario`：
调用场景（中文名）/ 次数 / 失败 / 平均耗时 / 输入 token / 输出 token / **其中思考**。
实测接口返回样例：

```json
{"dialog":{"calls":2,"averageMs":2056},"dialog_deep":{"calls":1,"averageMs":12664},
 "extract":{"calls":1,"averageMs":926,"promptTokens":892,"completionTokens":40,"reasoningTokens":0}}
```

## 8. 对话省电档 `dialog_fast`：只对"明确的寒暄/确认短句"关思考（2026-09-13 做并实测）

**规则**（纯程序，见 `DialogModeDecider`；判定顺序就是"宁可不省电"）：

| 条件 | 不满足时 |
|---|---|
| 没有附件/引用内容 | 回默认档（有资料要读） |
| 去空白后 ≤ 12 字 | 回默认档（消息较长） |
| **整句**命中寒暄/确认白名单（在吗/好的/谢谢/收到/嗯嗯/晚安/ok…） | 「晚上好，随便聊聊」这种**不算**——它是要聊天，不是纯寒暄 |
| 不含任何"要做事情"的线索词（提醒/查/搜/记/任务/文件/为什么/仔细…约 80 个） | 回默认档（含线索词） |

**开关**：`llm.dialog-fast.enabled`（默认 true，一个配置就能整体回滚）。省电档在指标里是独立场景 `dialog_fast`。

**实测**（同一个无计划测试用户）：

```
「好的，谢谢」  → 对话档位 mode=fast（寒暄/确认类短句）
                  scenario=dialog_fast ms=993 thinking=off maxTokens=0 正文=12字 思考=0字
「今天几号来着」→ 对话档位 mode=normal（不在寒暄白名单里）
                  scenario=dialog ms=2212 thinking=default 思考=547字
```

即：该省的地方从 2.2 秒 / 547 字思考降到 1 秒 / 0 字思考；不该省的地方一个字都不省。

**踩到的坑（就是这次验证抓出来的）**：第一版上线的省电档**根本没生效**——日志里 `scenario=dialog_fast … thinking=default maxTokens=4096`。
原因有两个，都很隐蔽：
1. `llm.thinking.disabled-scenarios` 在 `application.yml` 里有**非空默认值**（`extract,schedule_parse`），
   而**非空的配置值会整体覆盖代码里的默认集合** → 代码里加进 `DEFAULT_OFF` 的 `dialog_fast` 被盖掉了；
2. `maxTokensFor()` 对新场景走到了 `structuredMaxTokens`（4096），给对话套上了结构化档的上限。
**教训**：加新场景时，**代码默认集合与 yml/compose 的默认值必须同时改**（commit `02f850e` 修的就是这两处）；
而且"档位配置生效"这件事**必须看日志里的 `thinking=` 与 `maxTokens=`，不能只看代码**。

## 9. 工具集裁剪：没有备考计划就别背 18 个考试工具（2026-09-13 做并实测）

**规则**（`ToolSetTrimmer`，三个条件同时成立才收起来）：

1. 该用户**没有备考计划**（`ExamService.plan(userId) == null`；有计划的用户照常全量下发）；
2. 这条消息里**没有考试线索词**（考研/备考/复习/打卡/错题/进度/科目/数学/英语/政治/408/单词/计划/任务…约 30 个）；
3. **最近 4 轮**用户消息里也没有（避免"刚聊完考研、下一句『好』就把工具收走"）。

**`saveExamPlan` / `viewExamPlan` 永远保留**——否则用户第一次说"帮我建考研计划"时，建计划的工具恰好被裁掉，功能直接废了。
这是这个方案最大的坑，写在 `ToolSetTrimmer` 的类注释里提醒后来人。

**开关**：`agent.tool-trim.enabled`（默认 true）、`agent.tool-trim.history-turns`（默认 4）。

**实测**（生产）：

```
无计划用户「晚上好，随便聊聊」→ 本轮工具集已裁剪 保留 32 个 / 裁掉 18 个（考试组…）
同一用户「帮我建考研计划：2027-12-25 …」→ 未裁剪（提到考研）→ saveExamPlan 调用成功、exam_plan 落库 1 行
已有计划的用户「好的，谢谢」→ 未裁剪（有计划）→ 同时走 dialog_fast 省电档
```

省下的是每轮 prompt 里 18 段较长 schema（考试组描述普遍 60~120 字），具体 token 数**没有直接测到**
（流式响应不带 usage，这也是"下一步"里想开 `stream_options.include_usage` 的原因）。

## 10. 怎么验证

1. 模拟器发一条能同时触发三条链路的消息：`我叫小林，在做毕业设计，导师是张老师。顺便提醒我明天下午三点给张老师发开题报告。`
2. 等 35 秒（记忆提取是后台异步的），看日志：
   - `scenario=dialog … thinking=default … 思考=NNN字`（对话仍在思考）
   - `scenario=extract … thinking=off temperature=0.0 reasoningTokens=0`
   - `scenario=reminder_parse … thinking=default`（已收回思考；温度仍是 0.0）
3. `GET /api/admin/metrics/runtime`（带口令）看 `llmByScenario`。
4. 功能面：日志有「记忆提取完成」、`reminder_task` 真建了行、记忆写回了库。
5. 提醒修复单独验：`每天早上七点半提醒我看英语` → 库里 `0 30 7 * * ?` + 明天 07:30；
   `每3天提醒我浇一次花` → `0 0 9 */3 * ?`；`提醒我等会儿给妈妈打个电话` → **仍然反问**、不建行。

**两个踩过的坑**：
- 日志里的 `thinking=off` 是按**配置**算的，不是按实际发出去的请求体算的。那次 A/B 就是故意把 body 换成 `{"noop":true}`，
  此时日志仍写 `thinking=off`，**要看 token 数字才准**。
- **别在提取跑完之前删测试用户的行**：`MemoryExtractionScheduler` 是延迟异步的，删 `user_profile` 会让它抛
  `BizException: 用户不存在`（看着像 bug，其实是测试脚本竞态）。测完先等「记忆提取完成」再清理。

## 11. 明确没做（以及为什么）

- **不做"分配资源的 agent"**：路由本身要一次 LLM 调用，QQ 首字延迟直接翻倍；出错时"错在路由器还是执行者"不可观测；
  而且"让模型自己记得判断"这类设计在本项目翻过车（坑 27、39）。档位由**调用点**决定，纯程序、可预测。
- 对话侧的自适应（短闲聊关思考 / 长任务开思考）**还没做**：实测对话首正文 0.7~1.3 秒；要做也应该先上"程序规则"。
  已落地的"让模型参与"只走了**升级**这条路（`thinkDeeper`），因为它判错不会更差。
- 流式响应没有 `usage`（除非开 `stream_options.include_usage`），所以 dialog 的 token 记 0，思考量用**字符数**近似。

## 12. 下一步候选（按收益排序）

1. **流式开 `stream_options.include_usage`**：现在对话/省电档的 token 记 0（用字符数近似），
   "工具裁剪到底省了多少 token"这种问题**没有数字能回答**。
2. 工具裁剪扩到第二组（媒体工具：没有已存文件、没带附件、消息里也不提文件/图/课表）。
3. 搜索深度（固定 3 篇正文 × 1200 字）与记忆召回预算（core 16/2200、work 15/1500、context 40 轮/12000）按问题类型动态调。
4. ~~`dialog_deep`（升档）实测有过 28.5 秒一轮——要不要给它一个"超过 N 秒就先发一句缓冲"的体验设计。~~
   **已做**（§13）：升档成功时立刻发一句「这个我得仔细想想，稍等我一下…」，开关 `AGENT_DEEP_NOTICE_ENABLED`。

## 13. 收尾自查（2026-09-13 晚）：一轮全量复查抓到的东西

复查方式：三个独立子代理分别审「省电档规则 / 工具裁剪 / 媒体与提示词」，逐条**拿证据反驳**（能反驳掉的就不算 bug），
再在真机上跑规则表与风险探针。结果是 **3 个真缺陷 + 1 个 UI 漏字 + 若干"确认但先不改"**。

### 13.1 真缺陷（已修，commit `9301d82`）

1. **裸「好」在模型刚问过问题时会走省电档**（`DialogModeDecider`）：原来只看当前这一句，于是"要不要我提醒你？"→「好」被判成纯确认，
   而**确认之后往往正是要做事的时刻**（关思考还可能顺带少调工具）。现在多传一个参数 `previousAssistantText`：
   上一条 assistant 消息末尾出现 `？`/`?`（取最后 200 字）时，裸确认**一律回默认档**。规则表 12 条复测：
   11 条不变，「哈哈哈哈哈」原来会被判成"纯确认"（`BARE_YES` 里含"哈哈"），现在由 `PURE_LAUGHTER`(`^[哈嘻嘿呵]{2,}$`) 单独识别为寒暄 → 仍走省电档。
2. **零温度集合的默认值也用错了兜底**：`temperatureOverride()` 在没有配置时回落到 `DEFAULT_OFF`（思考默认关的场景），
   这跟"提取/排程要 0 温度、对话不要"是两件事，一旦有人只改思考集合就会互相牵连。现在拆出独立的 `DEFAULT_ZERO_TEMPERATURE`
   （extract / reminder_parse / schedule_parse / archive）。
3. **升档后的长等待没有任何反馈**：`thinkDeeper` 之后这轮实测 12.7 秒（最长 28.5 秒），用户只看到"正在输入"。
   现在升档成功顺手调 `ToolStatusService.pushNotice(...)` 发一句缓冲话；它**不受**逐条工具进度开关（`progressEnabled`）限制、
   但**每轮最多一条**（复用 `context.sent`），开关 `agent.deep-notice-enabled`。
4. **面板「按场景」表格把 `dialog_fast` 显示成英文键名**（`LlmPanel.vue` 缺标签）：补成「对话（省电档：寒暄/确认类）」。
   这是纯展示 bug——数据一直是对的，只是人看不懂。

### 13.2 确认存在、但故意先不改（附原因）

- `MediaStorageService.list()` 只在**最新 100 行**（`maxListResults*5`）里做内存过滤 → 更早的、**后来才写入内容说明**的文件搜不到。
  正确修法是把 `LIKE` 下推到 SQL；但这属于另一块（媒体检索）的改动，且当前库只有 6 个文件，**没有实际影响**。
- `purge`/遗忘重写备份时只处理 `*.zip`，不留 `backup/<yyyyMMdd>/` 老目录——服务器上确认**只有 `20260913.zip` + `media/`**，老格式不存在。
- `OpenAiRequestFactory.toJsonNode` 每轮把 32~50 个工具 schema 重新序列化一遍：想缓存得先证明"缓存前后字节完全一致"，
  属于热路径上的优化，收益不确定 → 不做。
- `ToolSetTrimmer` 在"无关键词"的那一轮会查一次 `ExamService.plan(userId)`（DB）：一次轻查询，**先不缓存**。
- 工具轮中途失败时用户只收到「抱歉，我这边出了点小问题」，**已经查到的结果没有给用户**——改法涉及失败的语义，另起一轮再说。
- `DescriptorPanel.watch(tick)` 对**非当前页签**也会刷新（多打几次接口，不影响正确性）。
