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

## 4. 怎么验证

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

## 5. 明确没做（以及为什么）

- **不做"分配资源的 agent"**：路由本身要一次 LLM 调用，QQ 首字延迟直接翻倍；出错时"错在路由器还是执行者"不可观测；
  而且"让模型自己记得判断"这类设计在本项目翻过车（坑 27、39）。档位由**调用点**决定，纯程序、可预测。
- 对话侧的自适应（短闲聊关思考 / 长任务开思考）**这轮没做**：实测对话首正文 0.7~1.3 秒，收益不如结构化场景明确；
  真要做也应该先上"程序规则"，而不是让模型判断。若真要让模型参与，唯一推荐的形态是 **`thinkDeeper(reason)` 工具**——
  在已有上下文里申请"下一轮用思考档重跑"，配单轮上限 + 日额度；判错最坏只是"没升级"。
- 面板「模型与搜索」页还没加**按场景的表格**（数据已经在 `/api/admin/metrics/runtime` 里了，前端没渲染）。
- 流式响应没有 `usage`（除非开 `stream_options.include_usage`），所以 dialog 的 token 记 0，思考量用**字符数**近似。

## 6. 下一步候选（按收益排序）

1. 面板把 `llmByScenario` 渲染成一张表（现在只能看接口/日志）。
2. 48 个工具 schema 每轮全量下发 → 按用户状态裁剪子集（无考研计划的人不该背 20 个考试工具的 schema）。
3. `max_tokens` 至今没设：思考 token 也计费，给每场景一个上限能防极端长思考。
4. 搜索深度（固定 3 篇正文 × 1200 字）与记忆召回预算（core 16/2200、work 15/1500、context 40 轮/12000）按问题类型动态调。
