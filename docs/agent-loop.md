# Agent 循环：计划、失败分级、反思

> 内容日期：2026-10-25（第一版）。对应提交 `f5f9318` / `cbafab5` 之后的生产版本。
> 动 `AgentLoop` 之前先读这篇，尤其"三条硬规矩"。

## 1. 一轮对话的真实流程

```
系统提示（人设 + 记忆 + 工具清单）
  → 历史消息 + 本轮用户消息
  → for round in 0..maxRounds:
      ① 轮内注入（用户在你干活时又发来的话，≤2 次）
      ② 钱够不够：超过软预算 → 最后一轮（收走工具、要求文字收尾）
      ③ round 0 且 plan.mode=inline → 要求它顺手写「计划：1) 2)」
      ④ 流式调模型（首轮 dialog_first=high，之后 dialog=low）
      ⑤ 有工具调用：
            · 进度行（同一工具每轮只报一次）
            · 第一个工具跑超 30 秒 → 心跳补「还在…」
            · 失败分类（TRANSIENT / FIXABLE / STRATEGIC）
            · 第一轮就调了 ≥2 个不同工具 → 走一次真 Planner
            · 策略性失败 → 一次独立反思（JSON）→ 注入重规划
            · continue
      ⑥ 没调工具、有文本 → 收尾（尾注 + 参考来源）
      ⑦ 没调工具、但发过语音 → 视为说完
  → 轮次/预算耗尽 → fallbackReply（带上失败工具与原因）
```

## 2. 三条硬规矩（改动前必看）

1. **工具执行的异常必须就地转成失败结果**。
   原来 `executeTool` 抛异常会逃出流式回调 `onCompleteResponse`，`latch.countDown()` 就执行不到，
   于是干等 120 秒超时、用户白等两分钟。现在每个工具外面都有 `try/catch`，异常变成
   `ToolExecutionOutcome.failure("工具执行异常：…")`，模型自己决定换方法还是收尾。

2. **计划/反思注入用 `UserMessage`，不要用 `SystemMessage`**。
   插在对话中部的 system 消息，DeepSeek/Qwen 会当成"高优先级指令"，
   容易盖过用户最新那句话的优先级。统一写成
   `UserMessage.from("（系统规划：…）")` / `（系统反思：…）`。

3. **普通对话也有软预算**（`agent.dialog-budget-yuan`，默认 0.3 元）。
   Planner 和反思各多一跳，极端情况下成本会浮。超预算就注入"收尾"提示并把工具收走，
   不会静默砍断。设 0 = 不限。

## 3. 计划（Plan）

| 模式 | 行为 | 成本 |
|---|---|---|
| `off` | 不规划 | 0 |
| `inline`（旧默认） | 第一轮要求模型在输出里顺手写「计划：1) …」 | 0（同一次调用） |
| `auto`（当前默认） | 第一轮就要调 **≥2 个不同工具** 时，真跑一次 `PlanService` | 约 ¥0.01 + 3~5 秒 |

为什么不是"每轮先规划"：我们首跳 p50 已经 12.7 秒，90% 的轮次是"一句话 + 一个工具"，
规划对它们没有价值。**触发条件是结构信号（工具个数），不是猜用户的话。**

Planner 产出 `{steps: [...]}`，注入后按它走；失败时反思给的 `revised_plan` 才是"重规划"。

## 4. 失败分级与反思

| 类型 | 判定 | 处理 |
|---|---|---|
| `TRANSIENT` | 超时/连接/限流/5xx | 代码层重试（`ToolInvocationService`），不让模型反思 |
| `FIXABLE` | 参数错/查无结果 | 错误原样回传给模型，它自己改（最便宜） |
| `STRATEGIC` | 工具内部重试过仍失败，或轮次过半一次没成 | 触发一次独立反思 |

反思（`TaskReflectionService`）：

- 走 `LlmScenario.REFLECT` 档，**独立一次调用**，每轮最多 1 次；
- 输出严格 JSON：`failure_type / root_cause / plan_is_wrong / should_replan / revised_plan / next_action`；
- `should_replan=true` → 注入「重新规划」步骤；否则注入「失败归因 + 下一步」；
- 结果写进 `task_reflection` 表（见 §5）。

## 5. 反思记忆（`task_reflection`）

为什么单独一张表：`memory` 是"关于他的事实"，`agent_self_note` 是"它对自己的描述"，
任务反思是第三种东西，混进去会污染两个注入通道。

| 项 | 做法 |
|---|---|
| 存 | `user_id / tool / failure_kind / failure_type / root_cause / next_action / created_at` |
| 读 | 同一工具再次失败时，取最近 30 天、最多 3 条，塞进反思提示词「上次遇到同类失败时的结论」 |
| 关 | `agent.reflect.memory-enabled`、`agent.reflect.memory-days` |

## 6. 进度条与流式消息（2026-10-25 补）

进度走 `/v2/users/{openid}/stream_messages`（`input_mode=replace`）。平台可能回：

- `40034019` 流式已结束；
- `40034024` msg_id 失效。

两者都会让进度条永久停住。**现在的处理**：记 warning + 用**普通被动消息**把当前进度发一条，
后面累积的行在收尾时补发一条（最多再一条）。日志：

```
[qq] progress stream rejected user=… reason=… -> 改用普通消息
[qq] progress fallback sent user=… lines=… ok=true
```

> 实测 2026-10-25 21:30 连挂两次、22:43 自己恢复：`msg_seq` 是全局自增（`QqMessageSequence`，1..65535），
> 失败与 WS 重连窗口有关（消息在断线期间发出、重连后才投递，`msg_id` 已过被动回复时效）。
> **不是代码问题，别为此改逻辑。**

## 6.5 首字延迟实测（2026-10-26）

埋点：每轮记录 `首字=Xms 总=Yms 工具=N`，首跳记录 `输入 tokens / 工具 schema 数`。

| 场景 | 首字 | 总时长 | 输入 tokens |
|---|---|---|---|
| `dialog_first=high`（0 工具） | **9097ms** | 9676ms | 12155 |
| `dialog_first=medium`（0 工具） | **2644ms** | 3110ms | 11213 |

结论：**瓶颈是首跳的思考时间，不是工具 schema 的预填充**（12k tokens 预填充只需几百毫秒）。
所以先降档，别急着裁工具。降档用面板覆盖（`POST /api/admin/llm/scenarios`），立即生效、可随时回滚。

## 7. 排查入口

```bash
docker logs --since 2h qq-agent | grep -E "执行计划生成|任务反思完成|已注入任务反思|progress"
```

- `执行计划生成 N 步` → Planner 真的跑了；
- `任务反思完成 type=… replan=…` → 反思触发；
- `遗忘级联：对话证据 N 条…` → 用户说"忘掉"且命中派生事实。
