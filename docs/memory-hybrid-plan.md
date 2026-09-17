# 记忆机制 hybrid 方案（前台工具 + 后台兜底 + 可观测）

> 2026-09-17 起草。**状态：待用户确认，未开工改代码。**
> 前置阅读：`docs/memory-extraction.md`（现状一页纸 + 被否方案）、坑 27/62/63/64。

## 0. 一句话

后台自动提取**保留**（它是"不丢记忆"的唯一保证），在它旁边加两条腿：
**① 前台工具**（用户说"记住"时当场写、当场回执，可见可核对）、**② 审计与面板**（每次提取为什么写/为什么空，一眼可见）。
省钱的着力点只放在**无风险的三处**：修掉 4096 截断、拉长合并窗口降频、提示词瘦身降单价——**不做"内容过滤"去挡可能重要的轮次**。

## 1. 目标 / 非目标

**目标**
1. 用户（和它自己）能**当场看见**一次记忆写入：写了什么、写进哪一类、失败原因。
2. "到底提取成功了没有"变成**可查的事实**（审计行），不再靠猜。
3. 空转成本下降（今天实测：13 次提取里 7 次零产出）。
4. 用户能**直接改/删**一条记忆（今天上午的真实诉求：课表纠正只能改图片的 extracted_text）。

**非目标（已知风险，明确不做）**
- ❌ 不删掉后台自动提取（漏记风险 > 省下的钱，且违背第一优先级）。
- ❌ 不做"内容过滤"（长度/数字/关键词）去挡提取——短消息里可能就是重要信息（`考公`、`不吃了`）。
- ❌ 不复刻"每天一次归纳（consolidation）"（6.2 已否决）。
- ❌ 不默认关闭思考（6.1 用户否过"省电档变傻"）。
- ❌ 不主动推送记忆写入通知（核心记忆里用户明确要求：不要主动推送任何东西）。

## 2. 现状（2026-09-17 实测）

**链路**：每轮对话后 `MemoryExtractionScheduler.schedule(userId)` → 静默 45 秒（最长拖 150 秒）→ `MemoryExtractor.extract()`：
读最近 20 轮 → `worthExtracting()` 门槛 → 一次带思考的 `extract` 调用（温度 0）→ 解析 JSON → 必要时**第二次小调用**（`reconcileCoreWithModel`，判断"是不是已有记忆的新说法"）→ `apply()` 写库（core / work / episode / completed / duplicates）。

**门槛现状**（`MemoryExtractor.worthExtracting`，窗口级）：窗口里任一 user 消息「去标点后 ≥5 字 或 含数字 或 含我/咱」→ 就跑。
- 真实数据核对：最近 25 条 ≤8 字的用户消息里，被挡掉的只有 `呜呜呜`/`？`/`全部` 这类噪声；带信息的（`不要推送任何东西` 8 字、`芊芊下午有课不` 7 字）其实都放行。**窗口是"最近 20 轮"、没有水位线**，跳过不会丢（下次有够格的消息会一起带进去）；唯一会丢的情况是"说完短句后连续 20 轮都没有够格消息或不再说话"。
- 结论：门槛**不是**今天空转的原因，但它确实是"有损"的，方向与第一优先级冲突 → 本方案改为**默认跑，只在整窗事务型时跳过**。

**成本（今天 11:32–12:56，13 次提取的真实账单）**

| 项 | 数值 |
|---|---|
| 调用次数 | 13（其中 7 次零产出、1 次撞上限截断） |
| prompt | 44,620 token（每次均值 3,432） |
| completion（含思考） | 24,437 token（每次均值 1,880） |
| 合计花费 | **0.133 ~ 0.189 元**（≈1.0~1.5 分/次）；其中**空转+截断 ≈0.07~0.10 元（>50%）** |
| 成本结构 | **思考占 ~70%**（输出 24,437 ≈ 0.098 元），prompt 占 ~30% |
| 对比 09-14 实测 | 当时 prompt 仅 900~1,100/次 → 现在 3,432/次（规则 12/13 + 已有记忆清单变长） |

**两个硬伤**
1. **4096 截断**：11:44:26 那次 `completionTokens=4096 reasoningTokens=4096` → 思考吃满、正文为空，静默当成"没什么可记"（≈0.03 元白烧 + 该记的没记）。reflect 早就单独给了 16384，extract 还在用 structured 的 4096。
2. **结果过期丢弃**：12:10:35「记忆提取结果已过期，放弃写回」——钱花了，写回被判过期整条丢掉。

## 3. 总体设计

```
用户消息 ──► 对话回复（模型可调 remember/recallMemory/forgetMemory）
                 │                        │
                 │                        └─► 共用质量闸 ──► core / work / episode
                 │                                            （留痕 + 可撤销）
                 └─► schedule(userId)：合并窗口 + 最小间隔
                          └─► extract（后台兜底，默认跑）
                                   ├─► 写库走同一个质量闸
                                   └─► 每次留一行审计 memory_extraction_run
                                                └─► 面板新页签「记忆」（descriptor，前端不动）
```

三条腿各自的职责：**前台**=可见与即时；**后台**=不漏；**审计**=可信。

## 4. 详细设计

### 4.1 前台工具（新增，挂 `AgentToolProvider` 自动注册）

| 工具 | 参数 | 成功返回 | 拒绝返回（都要说实话） |
|---|---|---|---|
| `remember` | `content`（必填，一句话）、`kind`=CORE/WORK/EPISODE（可选，缺省由程序判）、`evidence`（`conv:<id>` 或 `event:<id>`，走现有证据门） | `已记住 #137（核心记忆）：用户不喝咖啡` | `没记：与已有记忆 #137 重复（相似度 0.83）` / `没记：属于会变的信息（规则 12：课表/教室/临时日程/一次性数字）` / `没记：置信度不足（40 < 60）` |
| `recallMemory` | `query`（可选关键词）、`limit`（默认 10） | 列表：`#137 [核心] 用户不喝咖啡（2026-09-13，来源 conv:812）` | `没有匹配的记忆` |
| `forgetMemory` | `id`（必填）、`reason`（必填）、`confirm`（必须显式 true，否则拒绝） | `已停用 #137（保留原文，可回滚）` | `没确认，未动：需要用户明确同意` |

- **kind 由谁定**：模型给建议，程序按内容归位——含长期身份/偏好/原则 → CORE；含期限/未完成任务 → WORK；一段经历 → EPISODE；判不出来按 WORK。
- **写谁**：复用现成服务 `CoreMemoryService.add/replaceFromExtraction`、`WorkMemoryService.add/updateFromExtraction`、`EpisodicMemoryService.add`，`operator="TOOL"`，`MemoryProvenance(sourceMessageIds)` 带上真实来源。
- **证据**：`evidence` 走 `conv:<id>`（对话行）——现有 `selfRecall` 已证明这条死锁解法（坑 64①）；用户侧照抄，`AgentSelfTool.selfRecall` 是现成范本。
- **不主动推送**：工具只在被调用时产生回执，写进当轮回复；不做任何后台通知。

### 4.2 共用质量闸（把 `MemoryExtractor` 的私有校验抽成一处）

现状：`isAcceptable` / `isDuplicate` / `apply*` 都是 `MemoryExtractor` 的私有方法。
计划：抽出 `MemoryWriteGate`（新类，`memory` 包），两个入口：

```java
WriteVerdict write(String userId, Candidate candidate, MemoryProvenance provenance, String operator);
// 返回：written(id, kind) / duplicate(existingId, similarity) / rejected(reason)
```

规则（与后台完全一致，工具不绕过）：
1. `confidence >= memory.min-confidence`（默认 60）且内容长度合法；
2. 语义重复：与已有记忆相似度 ≥ `memory.dedup-threshold`（0.8）→ **不新增**，返回 duplicate（模型据此回执"没记，已有 #137"）；核心记忆的"新说法"仍走第二次小调用判定（`reconcileCoreWithModel` 逻辑复用）；
3. **规则 12/13 的程序化镜像**（只挡"明确会变"的，宽松优先）：正则命中「第X节 / 周X第 / 教室号形如 8B304 / x月x日上课 / 今天学了几小时」→ rejected(rule 12)；命中「记住/记一下/以后都」→ 直接放行（显式要求优先）；
4. 留痕：任何写入/停用都写 `memory_change_log`（before/after），`forgetMemory` 走 `CoreMemoryService.delete` → `ForgottenMemory`（**不删行**，可回滚）。

### 4.3 后台提取改造（三处，全部无内容风险）

| 改动 | 现值 | 改成 | 理由 |
|---|---|---|---|
| 结构化输出上限 | `LLM_MAX_TOKENS_STRUCTURED=4096` | **8192** | 堵住"思考吃满、正文为空"的白烧（今天发生 1 次） |
| 合并窗口 | `extraction-window-seconds=45`、`max-delay=150` | **180 / 300** | 同一段对话只提取一次；今天 13 次里至少能合并掉 4~5 次 |
| 最小间隔 | 无 | **`extraction-min-interval-seconds=180`** | 防止"每 2.5 分钟必跑一次" |
| 门槛 | `worthExtracting`（长度/数字/我） | **默认跑**；只在"整窗事务型"时跳过（窄名单见 §4.3.1） | 去掉有损门槛；跳过只发生在"确实没有可记内容"的窗口，且窗口无水位线、不会丢 |

#### 4.3.1 事务型窄名单（2026-09-17 用最近 3 天真实数据回放定稿）

回放方法：拉最近 3 天 `conversation_memory`（338 行 / 63 条用户消息），按现有调度逻辑（静默 45 秒 / 最长 150 秒）重建窗口，得到 **41 个窗口**，再按下列规则判"跳过/必跑"。

**必跑（任一命中即跑，优先级最高）**
`记住|记一下|记着|别忘了|以后|我习惯|我喜欢|我不喜欢|我是|我的|我打算|想先|备考|专硕|学硕|考研|目标|计划|生日|电话|地址|过敏|室友|女朋友|男朋友|改成|纠正|说错|不是…是|不要推送|别推送|不要做|底线|原则|保证|承诺`

**可跳过（整窗消息全部命中小类，且无一命中必跑）**
| 小类 | 触发词 |
|---|---|
| 课表/教室/时间 | `教室|课表|什么课|有课|上课|第.周|几点上|几点下|几号|星期|周几|时间|几点|哪个教室` |
| 元问题 | `刚刚…(记录|工具|说)|什么工具|什么功能|你想做什么|自己的想法|记录了什么` |
| 提醒操作 | `提醒|分钟后|小时后` |
| 资料操作 | `文件|资料|图片|截图|发我|保存好的` |
| 纯噪声 | 长度 ≤4 字、或纯语气词/标点（`呜呜呜`/`哭死`/`？`） |

**回放结果：41 个窗口 → 跳过 19（46%）、必跑 22**。被跳过的全是这五类（课表查询、元问题、资料操作、提醒、情绪噪声），没有发现"该记的被挡"的实例。
**注意（要在文档里保留的判断）**：`晚上的教室是8b`、`修改好啊，我都说了周二晚是这个教室` 这类**教室纠正**也会被判成课表类而跳过——这与规则 12（会变的信息不进记忆）一致，改教室走的是"媒体内容记录"，不是记忆。用户的体感差异来自规则 12 本身，不是这次过滤造成的。
**过滤挡不住的（坦承）**：纯知识问答（`哪个是置1，哪个是置0`）、定时任务指令（`查询我墨墨…推送给我`）、情绪长句——它们仍会各花一次调用。要再收紧得加"知识问答"规则，风险随之上升；先用审计数据观察两周再定。


补充：这两键（`extraction-window-seconds` / `max-delay-seconds`）**compose 没透传**，要改必须动仓库 compose（CI 部署时上传），`min-interval` 是新增键，同样要加。

### 4.4 可观测（审计表 + 新页签）

**新表（`deploy/mysql/V10__create_memory_extraction_run.sql`）**

```sql
CREATE TABLE memory_extraction_run (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  user_id           VARCHAR(128) NOT NULL,
  trigger_source    VARCHAR(32)  NOT NULL,          -- AUTO / TOOL / MANUAL
  window_turns      INT          NOT NULL DEFAULT 0,
  window_chars      INT          NOT NULL DEFAULT 0,
  prompt_tokens     INT          NOT NULL DEFAULT 0,
  completion_tokens INT          NOT NULL DEFAULT 0,
  reasoning_tokens  INT          NOT NULL DEFAULT 0,
  cost_yuan         DECIMAL(10,4) NOT NULL DEFAULT 0,
  duration_ms       INT          NOT NULL DEFAULT 0,
  verdict_json      TEXT         NULL,              -- 模型的原始判定计数（core/work/episode/duplicates/completed）
  written_ids       VARCHAR(512) NULL,              -- 实际写入的 id 列表
  skip_reason       VARCHAR(64)  NULL,              -- PRECHECK_TRIVIAL / TRUNCATED / STALE / BELOW_CONFIDENCE ...
  created_at        DATETIME     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_mer_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```
> `production` 是 `ddl-auto: validate` → **必须先在生产建表再部署**（坑：V7/V8/V9 同样的顺序）。

**页面**：新增 **descriptor 页签「记忆」**（后端给描述，`web` 不用改、也不用重新构建，见 `panels/registry.js` 的 `kind=descriptor`）。区块：
- `info`：今日提取 N 次 / 花费 X 元 / 写入 Y 条 / 跳过的原因分布
- `table`：最近提取记录（时间 / 触发 / 窗口轮数 / 判定 / 写入 / 跳过原因 / tokens / 花费）
- `table`：最近写入的记忆（id / 类型 / 内容 / 来源 / 时间 / 操作人）
- `actions`：`手动跑一次提取`、`撤销某条写入`（走 `forgetMemory` 的同一路径）

### 4.5 「改记忆」闭环（今天上午的真实诉求）

`recallMemory` → 找到 id → `forgetMemory(id, reason, confirm=true)`（旧行 SUPERSEDED + `ForgottenMemory` 留档）或 `remember` 新版本（走 coreUpdates 替换）。
**边界说明**：课表/教室这类"会变的信息"按规则 12 **不进记忆**，改课表仍然走"媒体内容记录"（`stored_media.extracted_text`，今天 12:12 已验证生效）。本方案不把两者混在一起，但会在工具描述里写清"课表类要改的是资料内容，不是记忆"。

## 5. 数据与迁移

- 新增 1 张表（V10），**不动存量行**。
- 不批改历史数据（那条错的教室号 `8B304.305` 仍按 09-14 的决定留到用户点头再说）。
- 部署顺序：生产建表 → push main → CI 部署 → 验证。

## 6. 新增/调整的配置键

| 键 | 默认 | compose 是否已透传 |
|---|---|---|
| `LLM_MAX_TOKENS_STRUCTURED` | 4096 → **8192** | ✅ 已有（改服务器 .env + 重建即可） |
| `MEMORY_EXTRACTION_WINDOW_SECONDS` | 45 → **180** | ❌ 要加 |
| `MEMORY_EXTRACTION_MAX_DELAY_SECONDS` | 150 → **300** | ❌ 要加 |
| `MEMORY_EXTRACTION_MIN_INTERVAL_SECONDS` | 新增 **180** | ❌ 要加 |
| `MEMORY_EXTRACTION_PREFILTER` | 新增 **transactional-only**（可设 `off` 完全不过滤） | ❌ 要加 |
| `MEMORY_WRITE_TOOLS_ENABLED` | 新增 **true**（关掉 = 三个工具不注册） | ❌ 要加 |

> 坑 36：compose 只透传 `environment:` 里列出的变量，漏一个就"改了 .env 却不生效"，且不报错。

## 7. 实施顺序（每步独立可验证、可回滚）

| 步 | 内容 | 风险 | 验证 | 回滚 |
|---|---|---|---|---|
| **1** | 审计表 V10 + `memory_extraction_run` 写入 + 面板「记忆」页签 | 极低（零行为改动） | 聊一轮 → 面板出现审计行（读了 20 轮、判 duplicates 3、写入 0、原因） | 删页签/停写入，表留着 |
| **2** | 硬伤三处：`LLM_MAX_TOKENS_STRUCTURED=8192`、窗口 180/300、min-interval 180、事务型窄跳过 | 低 | 连续对话 3 轮 → 审计里只有 1 行；不再出现 `completionTokens==maxTokens` | 三个 env 改回 |
| **3** | 前台工具 `remember` + `recallMemory`（走共用质量闸） | 中（新写入路径） | QQ 里说「记住：我不喝咖啡」→ 回执 + 库里一行 + 面板可见；说「记住：明天 8B304 有课」→ 回执按规则 12 拒绝 | `MEMORY_WRITE_TOOLS_ENABLED=false` |
| **4** | `forgetMemory`（需 confirm）+ 撤销动作 + coreUpdates 替换 | 中（改存量） | 改一条 → 旧行 SUPERSEDED、注入内容变化、可回滚 | 同上 |

## 8. 验证方法（每步都要落到真实对话）

1. 步 1：真实聊 3~5 轮 → 面板「记忆」页签对比"提取次数 vs 实际写入条数"，并核对审计里的 `skip_reason`。
2. 步 2：连续对话时数审计行（期望：一段 10 分钟的对话只产生 1 行）；grep 日志确认不再出现"截断"（`completionTokens == maxTokens`）。
3. 步 3：在 QQ 里分别说「记住：X」「记一下：明天 8B304 有课」「我不用你推送」→ 看三种回执是否与库内一致。
4. 步 4：让它在面板撤销一条 → 旧行变 SUPERSEDED、对话里不再被注入、`memory_change_log` 有记录。

## 9. 成本预估（按今天实测回放）

| | 改前 | 改后（估） |
|---|---|---|
| 提取次数 / 1.5h | 13 | **5~7**（合并窗口 + 事务型跳过） |
| 每次 prompt | 3,432 | 3,432（瘦身是第 2 阶段，可到 ~1,500） |
| 每次 completion | 1,880（含 1 次 4096 截断） | 1,880（无截断，但**有效产出**提高） |
| 花费 / 1.5h | 0.133~0.189 元 | **0.05~0.09 元** |
| 空转占比 | ~55% | ~15% |
| 前台工具新增成本 | — | 可忽略（工具 schema 几十 token，本身在对话轮里） |

## 10. 自评

**收益**：可见（回执 + 审计 + 页签）、可控（能改能撤）、更省（空转腰斩）、且**不牺牲"不丢记忆"**。
**代价**：多一条写入路径（靠共用质量闸与 `confirm` 兜住）；多一张表与一个页签（描述式面板可后端解决，不动前端）。
**最大不确定性**：事务型窄名单会不会误伤——所以第 2 步先把 `MEMORY_EXTRACTION_PREFILTER=off` 做成一键回退，并且**先离线回放**（把最近 3 天窗口跑一遍规则、列出"会被跳过的窗口"给你过目）再上线。

## 11. 实现记录（边做边记，都是实测踩到的）

### 11.1 步 1 已上线（2026-09-17，tag/镜像见提交 `audit what memory extraction actually did`）

- 表 `memory_extraction_run`（V10）**先在生产建好再部署**（`validate` 模式），部署后 `Started ... in 42.7s`、零 ERROR。
- 面板新增 **descriptor 页签「记忆」**（`/api/admin/panels` 里 `key=memory`）：提取账 / 提取记录 / 最近写入的记忆——**前端一行没改**。
- 本地实测一条真实提取：`window=2轮/124字 verdict=core=1 work=1 tokens=1071/1522 cache_miss=1071 cost=0.0072元`。

### 11.2 顺手挖出并修掉的两个真问题

1. **非流式调用根本没进账本**：`OpenAiCompatChatModel`（记忆提取/反思/提醒解析/排程解析/归档都走它）**没有用量接收端**，只有流式模型有 → "今天花了多少"一直少算这几类。已补 `setUsageSinks` + `publishUsage`，两边口径一致。
2. **Hibernate 对 `BigDecimal` 的默认列类型是 `DECIMAL(38,2)`**：本地 `ddl-auto: update` 建出来的列是 `decimal(38,2)`，于是 `0.0026 元`被四舍五入成 `0.00`；更要命的是生产 `validate` 会拿它跟迁移脚本的 `DECIMAL(10,4)` 比 → **类型不一致会让容器起不来**。实体必须显式写 `precision = 10, scale = 4`（已在 `MemoryExtractionRun.costYuan` 上标注）。

### 11.3 步 2 的进度

- ✅ 已上线（只改服务器 `.env` + 重建 38.6 秒）：`LLM_MAX_TOKENS_STRUCTURED=4096 → 8192`，堵住"思考吃满 4096、正文为空"的白烧。
- ⏳ 待做：**事务型窄跳过**（按"上一次提取之后的新轮次"判定，需要给提取加一个水位线）+ 窗口 45→180 / 150→300 + `extraction-min-interval-seconds=180`（这三个键 compose 没透传，要动仓库 compose）。
- 小尾巴：`memory_change_log` 的 `COMPLETE` 动作在面板上还没翻中文（现在显示英文原文）。

