# LLM 调用档位：按场景决定要不要"深度思考"（2026-09-13 做，2026-09-14 **已整块删除**）

> **现状（2026-09-14）**：用户看完实际体感后决定**不再按场景开关深度思考**，**全部场景都用模型默认的思考**。
> 代码侧 `llm.thinking.*`、`dialog_fast`、`DialogModeDecider` 都已删除，LlmScenario 只保留"温度 / max_tokens / 指标"
> 这三种用途。本文件保留**当初的实测与决策过程**，因为它解释了"为什么这个模型不需要我们去开思考"，
> 以及"哪些省电手段是免费的、哪些是要拿质量换的"。删除原因见 §15。

## 0. 一句话结论（当时的）

项目用的 `deepseek-v4-flash-vision-exp`（api.deepseek.com）**默认就在思考**——不是"没开深度思考"，而是**一直在开**。
所以这件事的本质不是"给 agent 加思考模式"，而是**"哪些调用应该把思考关掉"**：只要结构化输出的调用，**80%+ 的输出 token 都花在思考上**，关掉后实测快 2~3 倍、token 少 2/3，JSON 照样合法。

**当时默认关思考的是三个场景**（都是实测过、且失败代价小的）：

| 场景 | 为什么当时觉得关得起 | 后来的结论 |
|---|---|---|
| `extract`（记忆提取） | 同一条消息实测开/关结果一致（`episodes/work/core` 数量相同），8194ms/1516tok → 2254ms/493tok | **6 条消息复测后收回**：复合消息会漏记待办，而它是后台异步跑、省不到用户等待（§13.1） |
| `schedule_parse`（自然语言 → cron） | 8 个频率表达 **8/8 cron 完全正确**，且有 `isValidCron` 兜底（真错了只会反问用户） | 用户最终选择"全部思考"，也收回 |
| `dialog_fast`（寒暄/确认类短句，见 §8） | 只对"整句就是寒暄/确认 + ≤12 字 + 无附件 + 无做事线索词"的短句生效 | **整个省电档连同规则一起删除**（§15） |

**`reminder_parse` 与 `archive` 一直保持思考**：提醒时间解析错了=用户**漏掉提醒**；归档摘要进的是长期记忆（第一优先级是"记忆不丢失"）。

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

- `LlmScenario`（枚举 + ThreadLocal）：`DIALOG` / `DIALOG_DEEP`（模型申请升档后）/ `DIALOG_FAST`（§8 省电档）/
  `EXTRACT` / `REMINDER_PARSE` / `SCHEDULE_PARSE` / `ARCHIVE`。
  **没有给任何 Service 加构造器参数**（避开坑 45），只在调用点外面包一层 `LlmScenario.run(..., () -> chatModel.chat(...))`。
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
（那会儿流式响应记不到 token；2026-09-14 已修，见 §14）。

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
- 流式响应的 `usage` 原先被当成"没有"（见 §14：实测**本来就带**，现已照实记账）；在那之前 dialog 的 token 记 0、思考量用**字符数**近似。

## 12. 下一步候选（按收益排序）

1. ~~**流式开 `stream_options.include_usage`**：现在对话/省电档的 token 记 0（用字符数近似），
   "工具裁剪到底省了多少 token"这种问题**没有数字能回答**。~~
   **已做**（§14）：实测流式响应**本来就带 usage**，不用加任何请求字段，客户端把 usage 记下来即可。
2. 工具裁剪扩到第二组（媒体工具：没有已存文件、没带附件、消息里也不提文件/图/课表）。
3. 搜索深度（固定 3 篇正文 × 1200 字）与记忆召回预算（core 16/2200、work 15/1500、context 40 轮/12000）按问题类型动态调。
4. ~~`dialog_deep`（升档）实测有过 28.5 秒一轮——要不要给它一个"超过 N 秒就先发一句缓冲"的体验设计。~~
   **已做**（§13）：升档成功时立刻发一句「这个我得仔细想想，稍等我一下…」，开关 `AGENT_DEEP_NOTICE_ENABLED`。

## 13. 收尾自查（2026-09-13 晚）：一轮全量复查抓到的东西

复查方式：三个独立子代理分别审「省电档规则 / 工具裁剪 / 媒体与提示词」，逐条**拿证据反驳**（能反驳掉的就不算 bug），
再在真机上跑规则表与风险探针。结果是 **3 个真缺陷 + 1 个 UI 漏字 + 若干"确认但先不改"**。

### 13.1 真缺陷（已修，commit `9301d82` + `f60e009`）

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

### 13.3 复查的实测证据（2026-09-13 深夜，生产容器 `f60e009`）

规则表：`DialogModeDecider` 17 条用例（含上面三条新增/修正的）**17/17 通过**。

端到端（临时把 `WECHAT_CHANNEL_MODE` 改成 `simulator` 重建容器，跑完立刻改回 `disabled` 并删掉测试用户的行）：

```
「在吗」        mode=fast   reason=寒暄/确认类短句        → scenario=dialog_fast ms=677 thinking=off maxTokens=0 思考=0字
「提醒我一下」  mode=normal reason=含线索词「提醒」        → 机器人反问了两个问题（结尾是「？」）
「好」          mode=normal reason=在回答上一轮的问题，可能要真去做事   ← 新增的守卫在这里生效
复杂问题        mode=normal reason=消息较长              → 模型自己调 thinkDeeper，scenario=dialog_deep ms=11800 thinking=on 思考=2148字
                                                         → pushed 里有「这个我得仔细想想，稍等我一下…」← 升档提示真的发出去了
```

另外核对了：镜像 tag = `f60e009`；容器 env 里 `MEDIA_CONTEXT_MAX_FILES_PER_TASK=10`、`AGENT_DEEP_NOTICE_ENABLED=true`；
前端 bundle 与本地构建产物 **sha256 完全一致**（`f4a6301d…`，也就是「省电档」那个标签确实进了线上）；0 条 ERROR；面板 200 / 编码路径 401；`reminder_task` 18、`stored_media` 6、`exam_plan` 1、`scheduled_task` 2、机主 `last_channel=qq`（测试数据已清、通道已复位）。

## 14. 流式调用的 token 记账（2026-09-14 修）

**发现的经过**：复查时有人指出「面板『按场景』表格里 `dialog` / `dialog_fast` / `dialog_deep` 三行的 token 恒为 0，
而这三行恰恰是用来判断『升档值不值』的」。原来的注释写的是"流式响应没有 usage（除非开 `stream_options.include_usage`）"。

**直接问接口**（一次性的 Node 脚本，三次流式请求，一次不加任何字段；脚本是临时实验、没入库）：

```
流式（现状：不带 usage）        usageChunks=1 prompt=38 completion=82 reasoning=56
流式 + include_usage           usageChunks=1 prompt=38 completion=73 reasoning=44
流式 + include_usage + 关思考   usageChunks=1 prompt=12 completion=27 reasoning=?
```

结论：**这个接口的流式响应本来就带 `usage`**（每个流都会推一个带 usage 的 chunk，`completion_tokens_details.reasoning_tokens`
也在里面；关思考时该字段缺省）。所以问题从来不在服务端，而是**客户端没有去读**——原来的假设是错的。

**修法**（`OpenAiCompatStreamingChatModel`）：读流时顺手取 `usage`（取最后一个非空值），
结束时按场景写进 `RuntimeMetrics`，日志行也补上三个 token 数：

```
LLM 流式调用 scenario=dialog_fast ms=677 temperature=0.7 thinking=off maxTokens=0 正文=8字 思考=0字 promptTokens=? completionTokens=? reasoningTokens=0
```

拿不到 usage 时仍然是 0，`正文=/思考=` 的字符数继续兜底，**行为不回退**。

## 15. 2026-09-14：整块删除"按场景开关思考"（用户决定）

**触发**：用户问"和上一个大版本比，为什么感觉会变傻一点"。核查后的结论是——**主对话路径其实没动过**（省电档只覆盖
"≤12 字整句寒暄/确认"，他当时一次都没触发；工具裁剪对他也不生效，因为他有备考计划）。但用户随后明确要求：

> "那还不如全部都开算了，要不把这个功能删了吧，没必要了，我觉得，就默认思考就好了？"

**删掉的东西**（不留开关，直接从代码里移除）：

| 删除项 | 说明 |
|---|---|
| `LlmScenarioSettings` 的 thinking 集合 / `extraBody()` / `thinkingLabel()` | 连带 `llm.thinking.{disabled,enabled}-{scenarios,body}` 四个配置键与 compose 透传 |
| `LlmScenario.DIALOG_FAST` + `DialogModeDecider` 整个类 | 省电档**只**为"关思考"存在，思考不关了它就没有意义；AgentLoop 里的"对话档位"日志也随之删除 |
| 日志里的 `thinking=` 字段 | 已经恒为默认档，留着反而误导 |

**留下来的**（因为与思考无关，用户也明确说"温度先保留"）：

- `LlmScenario` 枚举 + ThreadLocal：仍用于**温度**（四个结构化场景 0.0）与**每场景 max_tokens**；
- 指标仍按场景分开（`dialog` / `dialog_deep` / `extract` / `reminder_parse` / `schedule_parse` / `archive`）；
- `thinkDeeper` 升档：现在只放宽"工具轮 8→16"与"流式超时 120→240 秒"，不再有"开思考"这一层含义。

**同一轮还改的**：记忆提取**不再每轮对话跑一次**——静默窗口 3→45 秒，且加"最多拖 150 秒"的上限（§16）。
用户的原话是"记忆提取每轮对话结束后异步跑，这样太耗资源了吧"。

## 16. 记忆提取的节奏：一场对话只提取几次，而不是每发一条一次（2026-09-14）

**问题**：`MemoryExtractionScheduler` 的静默窗口原来只有 **3 秒**——用户两条消息间隔超过 3 秒，就会**各自触发一次提取**。
实测：连续三条消息（间隔 3 秒）→ 三次提取，promptTokens 836 → 895 → 949（窗口互相重叠），每次都带 ~2000 思考 token。

**改法**：静默窗口 **3 → 45 秒**，同时加一个**上限** `memory.extraction-max-delay-seconds:150`：

```
用户还在说 → 每次新消息把提取往后推（最多推到"第一次排队 + 150 秒"）
用户停下来了 → 45 秒后跑一次提取，把这几十秒里说的都覆盖进去
一直说个不停 → 到 150 秒必跑一次，不会永远不触发
```

效果：一场 20 条的连续对话从"最多 20 次提取"降到"每 45~150 秒一次"（大约 3~7 次）。
提取本身用的是"最近 20 轮"窗口，所以**合并后不会漏内容**，只是让记忆晚几十秒出现（记忆不是即时可见的东西，
真正影响的是之后几轮，用户感觉不到）。

参数都在 `application.yml` 的 `memory.extraction-*` 下，想更省就调大 window，想更快看到记忆就调小。
## 16. 思考档位 reasoning_effort（2026-09-18，按用户要求恢复"按场景分档"）

用户要求：**对话 low、提取 high**。这次用的不是当年那个 `thinking:{"type":"disabled"}` 开关，而是
上游认的**档位参数** `reasoning_effort`（low / medium / high；不传 = 上游默认）。

实测（同一道"把这句话拆成事实条目"的题，各 4 次采样，deepseek-flash）：

| 档位 | 思考 token 均值 | completion 均值 | 耗时均值 |
|---|---|---|---|
| 不传（默认） | 3004 | 3077 | 13 988 ms |
| **low** | **984** | 1059 | **4 784 ms** |
| **high** | 2368 | 2461 | 11 165 ms |

→ low 的思考只有默认的 **1/3**、耗时的 **1/3**；high 与默认接近（默认略高于 high）。
（第一次只采 1 次时 low 反而比 high 高，是波动——**单样本会骗人，这种参数必须多采几次**。）

配置（两处默认值一致，compose 也透传）：

```
LLM_REASONING_EFFORT=dialog=low,dialog_deep=low,extract=high
```

格式「场景=档位」逗号分隔；**没列的场景不传这个字段**（保持上游默认，比如反射/提醒解析这些精度敏感的）。
认不出的档位会被忽略（宁可不传，也不传一个上游不认的值）。

实现：`LlmScenarioSettings.reasoningEffortFor(scenario)` → `OpenAiRequestFactory.buildPayload(..., reasoningEffort)`
→ 两个自研 ChatModel（非流式 / 流式）在调用时按当前场景取值。日志里能看到
`reasoningEffort=low` / `=high` / `=(默认)`，方便核对是不是真的传下去了。

上线后实测：对话往返 **3.8 秒**（思考 39 token），提取那次 `reasoningEffort=high`。

## 16. 每次调用都落库记账：`llm_call_audit`（2026-09-21）

**为什么**：在那之前只有"作业"按次落库（`agent_quest_run` / `memory_extraction_run`），
而**对话本身**——占开销最大头的每轮流式调用——只进了 `RuntimeMetrics` 的进程内计数，
重启归零、也没法按天或按场景聚合。用户问"最近花了多少钱"时，只能翻日志估（估出来 10 天 2.4~6.4 元，
但**按没按缓存命中算差 3 倍以上**，估的意义有限）。

**表**：`llm_call_audit`（DDL `deploy/postgres/V18__create_llm_call_audit.sql`），一行 = 一次调用：
`created_at / scenario / streaming / ok / duration_ms / prompt_tokens / completion_tokens /
reasoning_tokens / cache_hit_tokens / cache_miss_tokens / cost_yuan / error_message`。
**失败调用也记**（输入和思考照样计费，只记成功的会漏钱）。

**几个口径**：

- `cost_yuan numeric(10,4)` 由 `LlmCostLedger.price(hit, miss, completion, Instant.now())` 算好写进来——
  命中/未命中分开（命中价是未命中的 1/50）、**峰谷 ×2 按调用时刻定**；这里不重复定义价格。
- 按**调用**记，不是按对话轮：一轮里可能出现多次（正文 + 工具后继续）。想看"一轮"的成本，看相邻秒数成组。
- 它与 `RuntimeMetrics` 的分工：那个是进程内、给面板看"现在快不快"；这个落库、回答"这段时间花了多少"。

**接线**（沿用已有的解耦方式）：模型层只广播事实，不认识钱也不认识库——
新增接口 `LlmCallSink`（对应已有的 `LlmUsageSink`，后者只有 token、没有场景与成败，
所以另开一个而不是改签名），两个自研 ChatModel 在 `record(...)` 里广播 `LlmCallEvent`，
实现类 `LlmCallAuditRecorder` 负责搬运与写库。**写库失败只记 WARN**，绝不把用户的回复带崩。
开关 `llm.audit.enabled`（`LLM_AUDIT_ENABLED`，compose 已透传，坑 36）——真出问题能一行环境变量关掉。

**查账**：

```sql
-- 按天
select created_at::date d, count(*), round(sum(cost_yuan)::numeric,4) yuan,
       sum(prompt_tokens), sum(completion_tokens), sum(cache_hit_tokens), sum(cache_miss_tokens)
  from llm_call_audit group by 1 order by 1;
-- 按场景（花钱多的在前）+ 缓存命中率
select scenario, count(*), round(sum(cost_yuan)::numeric,4) yuan,
       round(avg(prompt_tokens)::numeric,0) avg_prompt,
       round(100.0*sum(cache_hit_tokens)/nullif(sum(prompt_tokens),0),1) hit_pct
  from llm_call_audit group by 1 order by 3 desc;
```

`LlmCallAuditRecorder` 另有 `byDay(days)` / `byScenario(days)` / `todayYuan()`，供面板后续直接读。

## 17. 把固定前缀做成可缓存：把记忆块挪出系统提示词（2026-09-23）

**问题**（用户说"太贵了"，实测下来最大的那一刀）：

新账本上线后第一次真实对话就露出真相——`llm_call_audit` 里两轮 dialog：

| 调用 | prompt | 命中 | 命中率 | 花费 |
|---|---|---|---|---|
| 09-23 03:27 dialog | 10 336 | 256 | 2.5% | 0.0108 元 |
| 09-23 14:57 dialog | 10 389 | 256 | 2.5% | 0.0214 元（峰时 ×2） |

也就是说，每轮 10k 输入里 **97.5% 都按未命中价**（1 元/百万）在烧，而命中价只有 0.02 元/百万——**差 50 倍**。

**根因**：`AgentPromptBuilder` 当时拼的是
`人设 → 插件段 → 【长期核心记忆】→【与当前问题相关的工作记忆】→ 固定规则与边界（3907 字）`。
上游的上下文缓存**只认前缀**，而工作记忆是**按当前问题挑的、每轮都不一样**，于是从它往后
——3907 字固定规则 **以及排在系统提示词后面的 53 个工具 schema（约 5.6k token）**——全部作废。

**判定实验**（2026-09-23，直连官方接口，同一份请求只改顺序；脚本用完即删，key 只在服务器上读）：

| 用例 | prompt | 命中 | 命中率 |
|---|---|---|---|
| 动态在前 / 固定规则在后 + 53 工具（当时的线上顺序） | 6817 | 0 | **0.0%** |
| 固定规则在前 / 动态记忆在系统提示词末尾 + 53 工具 | 6816 | 1024 | 15.0% |
| **系统提示词全静态 / 记忆挪进用户消息 + 53 工具** | 6817 | 6656 | **97.6%** |
| 系统提示词全静态 / 不带工具（对照） | 1253 | 1024 | 81.7% |
| 纯静态 + 53 工具（无任何动态内容，对照） | 6797 | 6656 | 97.9% |

三个结论：① 动态内容放在固定规则**前面**时缓存是 0；② 只把规则提前、记忆挪到系统提示词**末尾**，
规则能命中但**工具 schema 依然不命中**（因为它在系统提示词之后）；③ **只有系统提示词整体静态**，
工具 schema 才一起进缓存。另外顺带量到：**53 个工具 schema ≈ 5.56k token**（6817 − 1253）。

**改法**：`AgentPromptBuilder.build()` 只留 人设 + 插件段 + 固定规则（**逐字节静态**），
记忆块由新的 `AgentPromptBuilder.memoryBlock(core, work)` 生成，拼在**本轮用户消息的最前面**，
包在 `<长期记忆>…</长期记忆>` 里；规则 5 补了一句"这个标签里是系统给的背景，可以放心用、
不用确认"，免得被当成"需要先向本人确认的第三方资料"。
消息内容不变、只是搬了位置，**不落库**（历史里存的仍是用户原话），所以下一轮前缀照样稳定。

**预期收益**：按实测的 10 336 token / 轮估算，未命中 10 080 → 约 250，
单轮 dialog 从 **0.0108 元降到约 0.0014 元（≈7.5 倍）**，峰时同比例。

**注意（地雷）**：这个改法的前提是"系统提示词里没有任何每轮变化的内容"。
现在唯一会破坏它的是**插件段**——`SelfLoader`（"它自己那侧"）的 `lessons` 是按当前用户消息挑的，
生产上 `MEMORY_SELF_ENABLED=false` 所以是空的。**哪天要开自主模块，得把那段也挪进记忆块**，
否则缓存立刻回到 0，成本涨回 7 倍。

**工具集裁剪（方案 B）还做不做**：实测下来**先不做**——工具 schema 一旦进了缓存就是 0.02 元/百万，
每轮省不到 0.0001 元；收益只剩"缓存冷掉的第一次调用"和一点延迟，
不值得再动一轮工具集（用户 2026-09-23 拍板先只做 A）。

## 18. 效果复核 + 记忆提取的两处改动 + 面板上的思考强度滑块（2026-09-29）

### 18.1 §17 的复核：成了

真机发了消息之后账本从 3 行涨到 53 行，正好做前后对比：

| | dialog 调用数 | 平均命中率 | 单次花费（非峰时可比） |
|---|---|---|---|
| 改造前 | 2 | **2.5%** | **0.0108 元** |
| 改造后 | 45 | **81.2%** | **0.0011~0.0015 元** |

最典型的一条：`prompt=10981 hit=10752 miss=229` → 命中 **97.9%**，0.0015 元（还是峰时价）。
**约 8~9 倍**，与预估吻合。

### 18.2 新暴露的大头：记忆提取（A + B 两处改动）

六天总账 **0.3923 元 ≈ 0.065 元/天**，构成是：

| 场景 | 次数 | 合计 | 单次 | 命中率 |
|---|---|---|---|---|
| dialog | 45 | 0.2066 | 0.0046 | 81.2% |
| **extract** | **7** | **0.1827（47%）** | **0.0261** | **0%** |
| reminder_parse | 1 | 0.0030 | 0.0030 | 0% |

**A：提取提示词把「当前时间」挪到规则之后**。`MemoryExtractor.buildPrompt` 原来第一句就是
`你是用户的长期记忆提取器。当前时间：2026-09-29 11:59。…`——跟 §17 的 dialog 一模一样的病：
上游缓存只认前缀，时间每轮都变，于是从第 10 个 token 起全部作废，规则那一大段（约 1.4k token）
一次也没命中过。现在时间挪到规则之后（动态内容从那里才开始）。
以后往这个提示词里加东西，规矩一样：**静态的往前放、每轮会变的往后放**。

**B：extract 的思考档从 high 降到 low**。它贵的不是输入而是输出：`completion=4323`，
其中**思考 4178**，输出占了这一单 71% 的钱。实测 low 的思考只有 high 的 1/3
（见 §13.1 的四次采样），所以用户决定把 extract 也降到 low——
`application.yml` 与 `docker-compose.remote.yml` 两处默认值同时改（坑 36：不同步就"改了不生效"）。
**风险已知**：记忆提取的质量换来的，用户知情并同意。

### 18.3 面板上的思考强度滑块（每个场景一个）

**为什么要它**：思考强度是"想试一下、不合适马上改回来"的参数，而配置默认值改一次要动
`.env` + 重启容器（还会打断正在跑的对话）。

- **表** `llm_scenario_setting`（V19）：`scenario` 主键 / `reasoning_effort` / `updated_at`。
  **一行 = 一个场景被面板覆盖过**；面板选「默认」= **删掉这一行**，回落到配置文件——
  这样"跟随配置"只有一个来源，不会出现"库里 low、配置 high、到底谁算"的歧义。
- **读取路径**：`LlmScenarioSettings.reasoningEffortFor()` 先问 `LlmScenarioEffortService`
  （内存里的 `ConcurrentHashMap`），没有覆盖才用配置值。**模型调用线程上没有任何数据库访问**：
  表只在启动时整表读一次，改动时写一次。
- **接口**：`GET /api/admin/llm/scenarios`（每个场景的 配置默认 / 面板覆盖 / 实际生效），
  `POST /api/admin/llm/scenarios` `{"scenario":"extract","effort":"low"}`，`effort` 传空串 = 恢复默认。
- **界面**：`LlmPanel.vue` 每个场景一行，滑块 4 挡（默认 / 低 / 中 / 高），改完立刻生效。
  滑块**只在进页面时读一次**、不跟 10 秒的 tick 一起刷新——否则会把用户正在拖的滑块冲掉（坑 57）。

**排错**：认不出的场景名**绝不能**退回 `DIALOG`（那会悄悄改掉对话的档位），
`LlmScenarioEffortService.parse` 认不出就返回 null、直接忽略那一行并打 WARN。

**坑（上线当天就踩到，比功能本身更值得记）**：滑块横跨整行，手机上想滚页面时手指很容易落在它上面，
而 `input[type=range]` **把竖向划动也当成改值**——实测一次误写 5 行覆盖值，
"跟随配置"被悄悄改成"面板设定"，界面开始说谎。三层一起修：

1. CSS 加 `touch-action: pan-y`（竖向划动交给页面滚动，横向才喂给控件）。
2. **但光靠它不够**：range 控件在 `touchstart` 那一刻就已经把值跳到手指位置了，等浏览器判定
   "这是滚动"时值早变了、松手照样触发 `change`。所以自己判定手势方向——`pointerdown` 记下坐标与**原值**，
   `pointerup` 比 `|dx|` 与 `|dy|`：竖着的（`|dy| > |dx|`）一律当"用户在滚页面"，把滑块拨回**按下时的原值**、
   不写库。键盘操作没有指针事件，仍由 `change` 兜底。
3. 再加一条"值没变就不写库"的护栏，挡掉"拨到了同一个位置"的空写。

**真机验证（2026-09-30，vivo V2362A，用 `adb shell input swipe` 造手势，每次都以库里的行数为判据）**：

| 手势 | 期望 | 实测 |
|---|---|---|
| 竖滑 3 次，**起手点故意落在滑块轨道上** | 不写库（只滚页面） | **0 行** ✅（修复前同样的动作写了 5 行） |
| 横拖到底 | 写 1 行 `dialog=high` | **恰好 1 行** ✅，界面同步显示「面板设定 · 高」 |
| 横拖回最左（默认） | 删掉那一行 | **0 行** ✅，界面回到「跟随配置」 |

3 次竖滑把页面滑到了底部，本身也证明"竖向手势确实交给了滚动"。

**验证的教训**：这类"手势会不会误触"的缺陷，用 `adb shell input swipe` 就能复现/验证，
但**它会同时动用户正在看的手机界面**（第一次做的时候用户正在刷视频，被我点跑了）。
**动别人手机的自动化要收敛**：先看前台是什么应用，一次只做必要的动作，最好先问一声。

**仍未验证的一环**：档位从 `reasoningEffortFor()` 进到请求体这一段（模型日志里的 `reasoningEffort=`）
要等一次真实调用才能看到——接口层已经证明 `effective` 是对的，剩下的是既有代码，
用户下次发消息时看一眼日志即可确认。



