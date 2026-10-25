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

## 当前状态

**尚未实跑**：2026-10-26 01:20 试跑时本机 Docker Desktop 未启动，无法起本地实例。
代码、用例、脚本均已提交，等 Docker 起来后按上面三步跑一次即可得到首个基线分数。
