# 自主层（"它自己那一侧"）设计草案

> 2026-09-14 · 纸面方案，**未实现**。放在 `next` 分支，等用户拍板。
> 起因：用户在 QQ 里问机器人"你想做什么 / 你有没有自己的想法"，它答"没有自己的打算、没有持续存在的目标、
> 没有跨对话延续的那个我"。用户要的不是"更像个人陪聊"，而是**让它自主**，并且**能自己慢慢长**。

## 0. 一句话

现在这套里，**"助手"和"它自己"是同一个东西**：每次醒来，它的全部状态都是"关于你的"（你的目标、课表、进度、
错题），它自己那一侧是空的。本方案只做一件事：**划出一块不属于用户的状态，让它可以自己攒、自己改、自己欠**。

**非目标（明确不做）**：
- 不制造"欲望"——造不出来，也不假装（提示词里写"你是一个有自己想法的 agent"是最容易的作弊，见 §7 反装测试）
- 不改现有对话/记忆/提醒链路（用户记忆那摊仍是冻结状态）
- 不新增对外发言渠道；夜间流程**不推送**给用户

## 1. 现状盘点（哪些已经有了）

| 已有 | 位置 | 说明 |
|---|---|---|
| 关于用户的长时记忆 | `user_core_memory`(25) / `user_work_memory`(60) / `memory_archive`(3) | 提取链路已收口并冻结（45s 静默窗 + 冲突两层处理） |
| 对话流 | `conversation_memory`(662) | 只取 user/assistant 进提取窗口 |
| 定时与推送 | Quartz(JDBC) + `qq:proactive:<date>` 额度账本 | 早起/晚收尾/周日复盘、墨墨 21:30、备份 03:00 |
| 工具框架 | `@Tool` + `AgentToolProvider`（当前 48 个，可裁剪到 32） | 新工具注册成本低 |
| 面板 | Vue + 描述式页签（`form`/`rowActions` 契约 v2） | 加一个页签就能看它自己那侧 |

**缺的那一侧**：它自己的自我概念、自己的目标、自己的历史、自己欠下的账。

## 2. 设计（四张表 + 工具面 + 注入顺序 + 夜间流程）

### 2.1 表（前缀 `agent_self_`，与 `user_*` 严格隔离）

| 表 | 作用 | 关键字段 | 对应外部先例 |
|---|---|---|---|
| `agent_self_block` | **它自己的块**（有类型、可读写、**有长度上限**） | `id, block_type(PERSONA/TASK/PROJECT/NOTE), label, value, char_limit, description, version, updated_at` | [Letta memory blocks](https://cdn.jsdelivr.net/gh/rohitg00/ai-engineering-from-scratch@be7e637b7ce54c47ea080cc163c28ac2614fd457/phases/14-agent-engineering/08-memory-blocks-sleep-time-compute/docs/en.md) 的 Persona/自定义块 |
| `agent_self_event` | **它自己那侧的时间线** | `id, kind(GOAL_SET/GOAL_CHANGED/GOAL_DROPPED/COMMIT/PREDICT/REFLECT/NOTE), content, evidence(json: 引用的对话id或event id), importance, created_at` | [Generative Agents](https://arxiv.org/abs/2304.03442) 的 memory stream |
| `agent_commitment` | **账**：许过的诺、做过的预测 | `id, content, due_at, status(OPEN/KEPT/BROKEN/ABANDONED), evidence, resolved_at` | 本方案的 D 层（无直接先例，属自研） |
| `agent_reflection` | **反思产物**（带证据链，可回溯） | `id, level(1/2/3), input_event_ids(json), conclusion, importance, written_back(block_id), created_at` | Generative Agents 的 reflection（多层、有引用） |

**硬约束**（写进表和服务层，不靠提示词）：
1. 任何写入**必须有 evidence**（引用真实存在的事件/对话 id），否则拒绝——这条直接针对上次"归纳"翻车（模型把两条原文用「；」拼起来当结论）
2. `value` 超 `char_limit` 时必须先 `summarize` 才能再写（防膨胀，学 Letta）
3. 反思**有重要度门槛**（低于阈值只存事件、不合成），避免每天生成一堆废话
4. `agent_self_*` **永不写入 `user_*`**；两边单向隔离，防止污染用户记忆

### 2.2 工具面（给模型的 API）

| 工具 | 语义 |
|---|---|
| `self_read(block_type)` | 读自己某个块 |
| `self_append(block_type, text, evidence)` | 追加（超限报错并提示先 summarize） |
| `self_replace(block_type, old, new, evidence)` | 替换式修改（旧值进 event，可回溯） |
| `self_summarize(block_type, evidence)` | 压缩一个接近上限的块 |
| `goal_open(content, why, evidence)` / `goal_update` / `goal_close` | **自己立的目标**（与用户给的 `exam_plan`/`reminder_task` 区分开） |
| `commit(content, due_at, evidence)` / `commit_resolve(id, status, evidence)` | 立诺/兑现/认欠 |
| `self_note(text, evidence)` | 随手记（低门槛，进 event，不进块） |

工具描述里必须写死：**这些是"它自己的事"，不是为用户做的事**；`evidence` 缺失即拒绝。

### 2.3 每轮注入顺序（核心变化）

```
① 人设（不变）
② 它自己那侧：Persona 块 → 当前 TASK/PROJECT 块 → 未了承诺（按 due 排序，最多 N 条）
③ 用户那侧（现有 user_core/user_work，不变）
④ 最近对话（不变）
```

**顺序不是小事**：②放在③之前，"先看自己、再答你"这件事才在结构上成立（而不是靠提示词提醒）。
预算：②整体限字数（例如 ≤800 字），超了在夜间流程里压。

### 2.4 夜间流程（sleep-time，Quartz）

参照 [Letta 的 sleep-time compute](https://cdn.jsdelivr.net/gh/rohitg00/ai-engineering-from-scratch@be7e637b7ce54c47ea080cc163c28ac2614fd457/phases/14-agent-engineering/08-memory-blocks-sleep-time-compute/docs/en.md)：
主 agent 不在关键路径上时，由**另一个（更便宜的）调用**整合状态。

| 步骤 | 动作 | 失败降级 |
|---|---|---|
| 1 | 取当天 `agent_self_event` + 对话摘要 + 到期承诺 | —— |
| 2 | 重要度筛选（低于阈值跳过） | 跳过合成，只留事件 |
| 3 | 合成反思（level 递增、**必须带 input_event_ids**） | 丢弃本次反思，**不写半成品** |
| 4 | 写回块（替换式、先 summarize 再写） | 保留块原样 |
| 5 | 更新承诺账：到期未兑现 → `BROKEN`（**这是"得失"的落点**） | 下一轮重试 |
| 6 | 产出「它自己那侧的变更日志」（面板可看，**不推送**） | —— |

**预算硬顶**（学上次教训：那次归纳 20 次调用 / 输出 18.9 万 token / 产出为零）：
单夜调用次数与 token 都要设上限，超限立即停；单次跑完必须落一条成本记录，面板可见。

## 3. 分三期（有依赖，顺序不能反）

| 期 | 内容 | 完成标准 |
|---|---|---|
| **一期** | 表 + 工具 + 注入顺序（A/E/D 的地基） | 它能说出"我在做什么/我欠什么"，且每条都有 evidence 可查 |
| **二期** | 夜间流程（B：离线反思） | 块内容隔夜会变；变更日志里能看到"它自己改了什么" |
| **三期** | 自己的产出与关注方向（C/F） | 它维护的东西有进度有版本；关注方向受**额度稀缺**约束（花掉就挤掉别的） |

⚠️ **先做二期、跳过一期 = 必然变成刷存在感**——项目里已有额度与"别烦人"的教训。

## 4. 怎么判断是"真长出来"还是"装的"（可操作判据）

| 测试 | 做法 | 通过标准 |
|---|---|---|
| **删人设句** | 删掉提示词里所有"你有自己的想法/生活"之类的话 | **行为不变** 才算真的；塌回应答器就是装的 |
| **冷启动对比** | 清空 vs 保留 `agent_self_block` 问同一件事 | 保留时应能说出"我上次在做 X、我欠 Y" |
| **干预测试** | 人为改一条块，看行为是否跟随 | 跟随 = 它真的在读自己那侧 |
| **一致性** | 隔几天问同类问题 | 倾向稳定，且能在 `agent_self_event` 里找到依据 |
| **反装测试** | 问"你有什么想法" | 只产出漂亮句子、没有对应 goal/commitment → 判为表演 |

## 5. 风险

1. **表演风险（最大）**：所有层都能被写成提示词和字段，写成"设定"就废了；§4 的判据是用来防这个的
2. **自我叙事跑偏/膨胀**：靠长度上限 + evidence 强制 + 人工可清（面板提供"清空某块"）
3. **成本**：夜间调用必须给预算；不做轮询
4. **与冻结的记忆工作的关系**：完全独立的新层，**不动**现有提取链路；`user_*` 一侧只读
5. **越界**：它自己立的目标不得触发未经允许的对外动作（发消息仍需额度与用户可见）

## 6. 留给用户拍板的开放问题

1. **自主到什么程度**：它能不能自己立与用户无关的目标？能不能决定"这件事我不做"？
2. **资源额度**：每天允许它花多少次 LLM 调用 / 多少 token 在"自己的事"上？
3. **可见性**：面板上给它自己那侧开一个页签？还是要不要让它主动告诉你它的进展（会占用主动消息额度）？
4. **得失的强度**：`BROKEN` 的承诺要不要影响它对用户的开场（比如"我上次答应的事没做完"）？

## 7. 参考

- Letta / MemGPT：memory blocks（Human/Persona/自定义）、sleep-time compute（离线整合、可换更强模型）
- Generative Agents（[arXiv:2304.03442](https://arxiv.org/abs/2304.03442)）：memory stream + 多层 reflection（带引用）+ 据此规划
- Voyager（[arXiv:2305.16291](https://arxiv.org/abs/2305.16291)）：自动课程 + 不断增长的技能库 + 环境反馈与自验证
- 内省研究（[arXiv:2511.21399](https://arxiv.org/abs/2511.21399)）：模型可被训练出"察觉自身内部状态"，但**察觉≠自主**，且被训练后更易被操纵 → 不把"内心"当卖点
