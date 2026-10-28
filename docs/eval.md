# 记忆评估（跑分）

> 内容日期：2026-10-26（第一版，**代码就绪、尚未实跑**）。
> 目的：改提示词/记忆策略之后，能用**同一组用例前后对比**，而不是上线后碰运气。

## 它评什么

| 类型 | 输入 | 判定 |
|---|---|---|
| `extract` | 一段合成对话 → 走真实提取链路 | 期望的事实（subject/object 片段）是否被抽出；**不该记的**（情绪、一次性、闲聊、否定）是否被记 |
| `recall` | 一条 query → 走真实 `recallFacts` | 渲染出的卡片里是否包含期望值 |

用例在 `src/main/resources/eval/memory-cases.json`，当前 **19 条**（12 抽取 + 7 召回）。
每条 `extract` 用例用**独立 eval 用户**（`eval-xxx-<id>`），互不污染；`recall` 用 `fromCase` 指向前面的抽取用例。

## 怎么跑（只能本地）

```bash
# 1) 本地起一套一次性 pg + redis（不要用生产）
docker run -d --name wv-pg -e POSTGRES_DB=wv_eval -e POSTGRES_USER=wv -e POSTGRES_PASSWORD=wv -p 55433:5432 pgvector/pgvector:pg16
docker run -d --name wv-redis -p 56379:6379 redis:7-alpine

# 2) 起应用（关键：本地 profile + 关 QQ + 打开评估开关）
export SPRING_PROFILES_ACTIVE=local
export DB_URL='jdbc:postgresql://localhost:55433/wv_eval'; export DB_DRIVER=org.postgresql.Driver
export MYSQL_USER=wv MYSQL_PASSWORD=wv
export REDIS_HOST=localhost REDIS_PORT=56379
export SPRING_QUARTZ_JDBC_INITIALIZE_SCHEMA=always
export QUARTZ_DELEGATE=org.quartz.impl.jdbcjobstore.PostgreSQLDelegate
export AGENT_CHANNEL_MODE=simulator QQ_ENABLED=false MEMORY_SELF_ENABLED=false SCHEDULED_ENABLED=false
export SERVER_PORT=18080 SERVER_SSL_ENABLED=false
export AGENT_EVAL_ENABLED=true          # 生产保持 false
export MEMORY_RECALL_KEYWORD_FALLBACK=true   # 本地没配 embedding key，靠它才能验召回
java -jar target/qq-agent-0.0.1-SNAPSHOT.jar

# 3) 跑分
python eval/run_eval.py --url http://127.0.0.1:18080
```

## 安全边界

- 接口默认**关着**（`agent.eval-enabled=false`），生产不开；
- 即使用错 id，服务也只接受 `eval-` 前缀的用户；
- 跑完按 `user_id` 清场（`memory` / `memory_fact` / `conversation_memory` / `memory_change_log` /
  `memory_extraction_run` / `task_reflection`），并额外删 `eval-xxx-%` 的子用户；
- 脚本拒绝非 `127.0.0.1/localhost` 的地址，避免误连生产。

## 成本与时长

一条 `extract` = 一次真实提取调用（约 ¥0.005~0.01、10~60 秒）。19 条大约 **¥0.1、5~10 分钟**。
适合改完提示词后手动跑一次，或放进夜间任务。

## 还没覆盖的

- **工具选择**（该调哪个工具 / 该不该调）——需要跑完整 Agent 循环，成本高，下一步做；
- **多轮连续性**（上下文压缩是否有效）；
- **反思/规划的效果**（触发后任务成功率有没有提升）。

## 首个基线（2026-10-26 02:05，本地一次性 pg）

```
总分 100%（19/19）
```

三次迭代的曲线：**31% → 42% → 73% → 100%**。但要说清楚：**后两次提升一半来自修产品、一半来自修评估本身**。

### 它抓出的两个真问题

1. **没配 embedding 时记忆召回直接返回空**（产品 bug）。
   本地没 `EMBEDDING_API_KEY`，`recallFacts` 第一行就 `if (!embeddingClient.isEnabled()) return List.of();`——
   事实明明在库里，问它却答"没记过"。生产虽然配了 key，但**接口挂掉/额度用完时会静默失忆**。
   已加兜底：向量不可用、embed 失败、向量没命中，三种情况可退化为**关键词匹配**
   （中文按 2~3 字切片 + 停用词过滤）。**但默认关着**——`memory.recall-keyword-fallback=false`：
   宁可"查不到"，也不拿粗匹配结果冒充语义召回（用户 2026-10-26 明确要求）。

2. **评估断言本身写错**（工具 bug）。
   期望只写 `object` 时，`contains(subject="")` 恒为 false → 6 条用例必挂；另外「三点」被模型规范化成「15:00」，
   断言没有容忍等价写法。都已修（空 = 不约束；支持 `objectAny`）。

### 一个必须知道的局限

100% 只代表「**和当前预期一致**」，不代表记忆系统完美。用例是照着真实能力写的，
改完提示词后**可能"分数很高但更蠢"**——所以它更适合当"回归网"（防止改坏），
而不是"质量上限"。真正要提升质量，还得看生产里真实的错答。

### 下一步可扩展

- 工具选择（该调/不该调、该调哪个）；
- 上下文压缩是否有效（多轮连续性）；
- 反思/规划触发后的任务成功率。
## 排序断言与"更难用例"（2026-10-28）

原来 `recall` 用例只检查**整段渲染里有没有**目标值——命中排在第 8 条也算通过，
所以它**测不出检索排序的好坏**，也就没法评估 BM25+RRF 这类"只改排序"的手段。

现在补了两件事：

1. `expectTopContains`：要求**第一条**命中就包含该值（排序断言）；
2. `expectContainsAny`：同一含义的多种写法都算对（"15:00" / "下午三点"），
   避免因为抽取时的措辞波动把测评判成失败——那是噪声。

**新增用例（5 条）**：

| 用例 | 考验什么 |
|---|---|
| `alias-classroom` / `alias-classroom-recall` | 别名提问（`周二·课`）能否召回规范写法 |
| `hard-three-courses` / `hard-one-of-three` | 三门课带教室，问其中一门 |
| `hard-many-rooms` / `hard-exact-room` | **六门课 + 精确 token 反查**（"8B203 是哪门课"），要求 top-1 正确 |

**当前基线：27/27（100%）**，含那条排序断言用例。

这个结果直接决定了第 ④ 项（BM25+RRF）的取舍：**我先把用例做难，它没掉分**——
说明在 4.4k 行、六路干扰项、精确 token 反查这些条件下，纯向量 top-1 已经是对的。
**没有证据支持"上 RRF 会更好"，所以不做**（要做也可以，但那是架构偏好，不是数据结论）。
## 混合检索（BM25 思想 + RRF）：已实现，**默认关闭**（2026-10-28）

代码在 `memory/HybridRecallRanker`，开关 `memory.recall-hybrid`（默认 `false`）。

**实现要点**：

- 词法一路：拉丁/数字按整段（`8b203` 这种房间号、型号必须整体匹配），中文取**字符二元组**——
  不引入词典分词，少一份维护成本；
- 融合用 RRF `1/(k+rank)`，`k=60`：向量分在 0~1、词法分是"命中几个 token"（0~5），量级不可比，
  所以只看名次；
- **只有词法命中的那几条参与词法排名**。这一条是踩出来的：最初把所有事实都排进去，
  结果真正的精确命中顶不上来（零命中的那些把名次挤宽了）。

**为什么默认关**：先按计划把评测做难（六门课六间教室、精确 token 反查、top-1 排序断言），
纯向量仍然 27/27。**没有掉分就没有可验证的提升**，所以不在生产开。
要评估就把开关打开再跑一次记忆评估，对比分数——不要因为"架构上更完整"就默认开。
### 开关打开后的实测（2026-10-28）

```
MEMORY_RECALL_HYBRID=true → [memory] 100%（27/27）
MEMORY_RECALL_HYBRID=false（默认）→ [memory] 100%（27/27）
```

**结论**：这条代码路径**能跑通、不劣化**（不是"写完没验证"），但**两组分数一样**——
说明在现有数据量与用例难度下它没有可测量的收益。

所以最终决定：**代码保留、开关默认关**。这比"打开了但没有证据"更诚实，
也比"删掉"更划算——数据量涨上来后，打开开关就是一次环境变量的事。
## 记忆重复体检（2026-10-28，脚本已落盘）

`tools/memory-duplicate-audit.py`——**只读**，走 ssh 读生产库，不改任何数据：

```bash
python tools/memory-duplicate-audit.py            # 默认阈值 0.55
python tools/memory-duplicate-audit.py --top 20 --out dump.tsv
```

基线（2026-10-28）：**196 条 ACTIVE，7 对近重复，涉及 14 条（7.1%）**，全是"同一件事换个说法"。

判读方式：
- 这个比例**持续下降** → 说明"阈值 0.75 + 相似带替代"在起作用；
- 出现"同一属性两个不同值"（例如两个教室号）→ 那才是真矛盾，要单独排查。
## "改主意"的语义替代：**机制本来就有**（2026-10-28 核实）

我原以为"改考/改时间/改地点"这类语义替代是个缺口，准备让抽取器新增一个 `relation` 字段。
核实后发现**已经有了**，而且是三处并行的机制：

| 层 | 机制 | 提示词里的规则 |
|---|---|---|
| 事实层 | `relation` + `targetSubject`（模型在同一次抽取里给合并结论） | 有 |
| 核心记忆 | `coreUpdates`（`oldContent` → `newContent`，系统留痕、旧条目 SUPERSEDED） | 规则 6：**改分数、改院校、改日期、改称呼、改偏好必须写 coreUpdates，不许新增平行的 coreCandidates** |
| 工作记忆 | `workConflicts` / `workCompleted`（`existingId` + 新内容 / 完成原因） | 有 |

**验证方式**（不加新代码）：往评估集加一条两轮用例——

```json
{ "id": "mind-change", "type": "extract",
  "conversation": ["我考研考 408 计算机统考", "我改考自命题了，不考 408 了"],
  "expectFacts": [{"objectAny": ["自命题"]}],
  "forbidFacts": ["408"] }
```

`forbidFacts` 是评估里已有的断言：只要还有 ACTIVE 事实包含 "408" 就算失败。
**结果：PASS**（总分 28/28）——说明模型确实输出了更新而不是新增一条平行的旧事实。

**结论：① 不需要改代码。** 再加一个 `relation` 字段只会和现有三套机制重复，
而且等于让模型在两处做同一个判断——那是增加歧义，不是增加能力。
