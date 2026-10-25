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
   已修：向量不可用、embed 失败、向量没命中，三种情况都退化为**关键词兜底**（中文按 2~3 字切片 + 停用词过滤）。

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
