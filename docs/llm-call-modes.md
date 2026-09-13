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

## 5. 让模型自己申请"更多预算"：`thinkDeeper`（2026-09-13 做并实测）

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

## 6. 每场景 `max_tokens`（2026-09-13 做）

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

## 8. 怎么验证

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

## 9. 明确没做（以及为什么）

- **不做"分配资源的 agent"**：路由本身要一次 LLM 调用，QQ 首字延迟直接翻倍；出错时"错在路由器还是执行者"不可观测；
  而且"让模型自己记得判断"这类设计在本项目翻过车（坑 27、39）。档位由**调用点**决定，纯程序、可预测。
- 对话侧的自适应（短闲聊关思考 / 长任务开思考）**还没做**：实测对话首正文 0.7~1.3 秒；要做也应该先上"程序规则"。
  已落地的"让模型参与"只走了**升级**这条路（`thinkDeeper`），因为它判错不会更差。
- 流式响应没有 `usage`（除非开 `stream_options.include_usage`），所以 dialog 的 token 记 0，思考量用**字符数**近似。

## 10. 下一步候选（按收益排序）

1. **工具集按用户状态裁剪**：50 个工具 schema 每轮全量下发（无考研计划的人不用背 20 个考试工具）——省 prompt token，
   也降低选错工具的概率。
2. 对话侧**规则化自适应**（寒暄/确认类关思考）：和 `thinkDeeper` 正好配对（默认省电、复杂时升档）。
3. 搜索深度（固定 3 篇正文 × 1200 字）与记忆召回预算（core 16/2200、work 15/1500、context 40 轮/12000）按问题类型动态调。
4. 流式开 `stream_options.include_usage`，把对话的 token 也真正记上（现在用字符数近似）。
