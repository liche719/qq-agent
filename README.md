# wechat-qq-agent

多用户 QQ 私聊长期陪伴 Agent 后端。Spring Boot 3.5 + LangChain4j + **PostgreSQL 16 (pgvector)** + Redis + Quartz(JDBC)。

定位：**长期陪伴型个人助手**。核心优先级：**用户长期记忆不丢失 > 系统稳定运行 > 交互体验友好**。

> 这份 README 面向"第一次看这个仓库的人"。
> **给 AI 编码代理的项目记忆在 [`AGENTS.md`](AGENTS.md)**（协作偏好、环境、部署、硬约定），
> 细节文档索引在 [`docs/README.md`](docs/README.md)。

## 技术栈

| 组件 | 选型 | 说明 |
|---|---|---|
| 框架 | Spring Boot 3.5 / Java 21 / Maven | |
| AI | LangChain4j + 自研 OpenAI 兼容 ChatModel（流式 + 非流式） | 任意兼容接口；当前默认 `deepseek-flash` |
| 存储 | **PostgreSQL 16 + pgvector** + Spring Data JPA | 2026-09-18 从 MySQL 8 整库迁来，见 `docs/pg-migration.md` |
| 缓存 | Redis | 上下文回退、消息幂等、主动消息日额度、任务状态 |
| 调度 | Quartz JDBC 持久化 | 提醒 / 定时任务重启自动恢复 |
| 搜索 | SearX-NG（自托管） | 只用实测可达的 6 个引擎；配置见 `docker/searxng/settings.yml` |
| QQ | QQ 官方机器人 WebSocket | 唯一通道；每个用户独立上下文、记忆与文件目录 |
| 面板 | Vue 3.5 + Vite（`web/`） | 构建产物进 `src/main/resources/static/`；生产用应用自带 HTTPS |

**微信 iLink 通道已于 2026-09-12 移除**，现在只服务 QQ 私聊。

## 快速开始

```powershell
# 1) 基础设施（Docker Desktop 需先启动）
cd wechat-agent-java
docker compose up -d mysql redis searxng     # 本地这份 compose 仍是 MySQL/Redis/SearXNG

# 2) 凭据：复制 .env.example 为 .env（已 gitignore），至少填 LLM_API_KEY
$env:LLM_API_KEY='…'

# 3) 起服务
mvn -DskipTests package
java -jar "target\wechat-agent-java-0.0.1-SNAPSHOT.jar"
```

- 面板：<http://127.0.0.1:8080/>（Vue 单页应用；本机回环免口令）。
- 前端是独立工程：`cd web && npm install && npm run dev`（Vite 5173 代理到 8080）；**要进 jar 必须先 `npm run build`**。
- ⚠️ 本机 JAR 与远程容器**共用同一个 QQ AppID，不要同时运行**，否则双开抢网关。

### 想端到端跑一轮「消息 → 模型 → 工具 → 回复」

生产是 QQ 通道，`/api/sim/*` 只在 `WECHAT_CHANNEL_MODE=simulator` 下注册。完整的本地一套（一次性 pg + redis、
独立端口、不碰生产也不碰 QQ）写在 **`AGENTS.md` §2 的「想端到端跑一轮」**。

跑起来后：

| 接口 | 用途 |
|---|---|
| `POST /api/sim/send` | 同步纯文本，直接返回回复 |
| `POST /api/sim/qq/send` | 异步模拟 QQ 消息，可带 `images` / `attachments` / `quotedContent` 等 |
| `GET /api/sim/replies?userId=…` | 取异步回复 |
| `GET /api/sim/memories` | 看该用户落下的记忆 |

每一轮工具往返都会写进 `conversation_memory` 的 `system` 行（`tool=<名> phase=result`）——
这是判断"模型到底有没有调工具、成功还是失败"最快的证据来源。

## 远程部署

推送 `main` 自动触发 `.github/workflows/deploy-remote.yml`：GitHub runner 构建镜像 →
**HMAC 验签分片上传** → 服务器 `wechat-deploy` 加载并只重建 `agent` 容器 → **部署后自检**
（首页 / 前端资源 / 无口令 401 / 编码路径 401 / 带口令接口 / 登录 / 域名证书），任一项不符就推 QQ 告警并把流水线置红。

- 生产面板：**`https://liche.cloud/`**（标准 443、Let's Encrypt 证书，浏览器绿锁）。
- 服务器 `/opt/wechat-agent-infra`，4 个容器 `wechat-agent-{java,postgres,redis,searxng}`。
- **数据卷任何情况下都不删除、不重建**。
- Secrets、受限部署命令、验签细节见 `docs/deploy-security.md`；完整步骤与排查见 `AGENTS.md` §3/§4。

## 功能模块

| 模块 | 包 | 说明 |
|---|---|---|
| 对话与工具循环 | `agent/` | 系统提示词逐字节静态（吃前缀缓存）；每轮注入当前时间与长期记忆 |
| 记忆（三层） | `memory/` | `memory`（一段话，`kind`=PROFILE/TASK/EXPERIENCE）+ `memory_fact`（有槽位的当前值，带取代链）+ `conversation_memory`（原文证据，**只能忘不能替**） |
| 媒体记忆 | `media/` | 保存/检索/审阅删除；一次任务读文件有上限；模型看完图要把"图里是什么"写回，否则以后搜不到 |
| 提醒 | `reminder/` | 解析/列表/取消/安全替换；到点只发一句话 |
| 定时任务 | `schedule/` | 到点**重跑一遍完整 Agent**（可搜索、可调工具）再发结果；与"提醒"不是一回事 |
| 考研规划 | `exam/` | 备考计划、每日任务、打卡、章节进度、错题本（1/3/7/15/30 天回收）、里程碑、正计时；三条推送 |
| 面试陪练 | `interview/` | 有题库 + 评分卡 + 复盘报告的模拟面试（不是"换个人设聊天"） |
| 墨墨背单词 | `maimemo/` | OIDC 授权（1 小时 access + 90 天 refresh）、进度查询、每日推送、面板页签 |
| 自主模块「它自己」 | `self/` | 独立作用域 `__self__`，攒够轮次触发反思；**有兴趣、有承诺、有预算（按钱算）、还有嘴**（想说话当场发） |
| 运维告警 | `alert/` | 60 秒巡检 QQ 网关 / PostgreSQL / Redis / Quartz / 磁盘 / 堆 / 备份新鲜度，只在"新出现问题或恢复"时推 QQ |
| 备份 | `backup/` | 每天 03:00 一个 zip + 媒体按 sha256 共享存一份；恢复步骤见 `docs/backup.md` |

**LLM 记账**：每次调用落 `llm_call_audit`（场景 / 流式 / 耗时 / token / 命中与未命中 / 费用 / 错误），
面板可看花费与各场景的**思考强度**（`llm_scenario_setting`）。计价口径见 `docs/llm-call-modes.md`。

## 目录结构

```
src/main/java/com/liche/wechatagent
├── agent/        # 消息编排、对话循环、工具循环、提示词组装
├── alert/        # 运维告警推送
├── backup/       # 每日备份与备份新鲜度检查
├── care/         # 低打扰主动关怀
├── channel/      # 通道抽象 + QQ 通道 + 模拟器 + 幂等 + 重连
├── command/      # 中文/斜杠指令解析与处理器
├── config/       # LLM 配置、场景档位、线程池、策略
├── controller/   # 面板 REST、模拟器、健康检查
├── document/     # PDF / DOCX 文本提取
├── exam/         # 考研规划
├── exception/    # 全局异常
├── interview/    # 面试陪练
├── log/          # 操作日志
├── maimemo/      # 墨墨背单词（含 OIDC）
├── media/        # 当前消息媒体授权、长期存储、检索、审阅删除
├── memory/       # 三层记忆、提取、向量召回、备份
├── metrics/      # 运行时指标
├── network/      # 出网安全（公开 URL 校验、DNS 固定）
├── reminder/     # 提醒实体/服务/解析/Quartz Job
├── schedule/     # 定时任务（重跑 Agent）
├── search/       # SearX-NG 客户端与搜索工具
├── self/         # 自主模块（判断/倾向/教训/它自己的作业）
├── tool/         # @Tool 注册表、策略、重试与失败语义
└── user/         # 用户档案（多租户）
```

## 配置

**不在 README 里逐条列环境变量了**——那份清单会随代码腐坏（删掉的键会一直躺在这儿误导人）。
权威来源是：

- **`src/main/resources/application.yml`** —— 全部键 + 默认值 + 每个键为什么这么设的注释；
- **`docker-compose.remote.yml`** 的 `environment:` —— ⚠️ **compose 只透传这里列出的变量**，
  漏一个就是"改了 `.env` 却不生效"而且**没有任何报错**（坑 36）。

常用的几个：

| 变量 | 默认 | 说明 |
|---|---|---|
| `LLM_BASE_URL` / `LLM_MODEL` / `LLM_API_KEY` | deepseek / `deepseek-flash` / 空 | OpenAI 兼容接口 |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` / `DB_DRIVER` | 默认值仍是 MySQL，生产由 env 覆盖成 pg | 切库只改 env |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | localhost / 6379 / 空 | |
| `WECHAT_CHANNEL_MODE` | `disabled` | 只有 `disabled`（跑 QQ）与 `simulator`（本地调试）两种有意义的值 |
| `QQ_ENABLED` / `QQ_APP_ID` / `QQ_CLIENT_SECRET` / `QQ_SANDBOX` | false / 空 / 空 / true | 机器人未发布前 `QQ_SANDBOX=true` |
| `TZ` / `APP_TIME_ZONE` | Asia/Shanghai | 时区（坑 29：还要代码里显式 `inTimeZone`） |

## 用户标识约定（多租户核心）

- **userId**：通道层从平台消息里提取的**唯一且不变**的用户身份（QQ 为 openid）。
  **全系统所有数据隔离（人设 / 记忆 / 提醒 / 幂等 / 上下文 / 日志）一律按 userId**。
- **botId**：只用于回复路由，不参与数据隔离。
- **msgId**：平台消息 id，与 userId 组成幂等键（Redis SETNX 24h）。
- 新增通道：实现 `WeChatChannel`，把平台用户标识映射成 `userId` 即可，下游零改动。

## QQ 能力边界

只把 QQ 开放平台文档明确提供的能力当作平台能力，其余是本地应用层逻辑，**不伪装成 QQ 原生功能**。

- **保留**：文本/Markdown、图片/视频/语音/文件富媒体（含预上传与分片上传）、单聊消息查询与引用解析、
  带 `message_id` 的撤回（官方限制发送后 2 分钟内）、WebSocket 事件与心跳/鉴权。
- **应用层**：长文本按长度拆分后用"继续/下一页"普通文本翻页；确认用普通文本令牌；
  任务重试、状态快照、运行时指标、长期记忆都由本服务负责。
- **不保留**：官方未提供的原生分页控件、动态确认按钮回调、超时后无消息 ID 的撤回。
- **群聊**：默认关闭（`QQ_GROUP_ENABLED=false`），当前回归范围只有私聊，**不要把群聊当已上线功能**。

## QQ 文件与引用消息

- 私聊直接发 PDF / DOCX 提问即可：文本型直接提取；扫描型转图片交给多模态模型。
- 临时文件只用于当次请求；Agent 判断有长期价值时会自行按内容命名并保存到该用户的
  `stored-media/{用户隔离目录}/`，数据库只存元数据与相对路径。Agent **无法指定磁盘路径或用户 ID**。
- 删除前必须先审阅内容并取得一次性短期令牌，且只移入该用户的 `.trash/`，**不永久擦除**；
  只有当前消息明确要求删除才算授权（"怎么删除""不要删除"都不算）。
- 引用消息：会读取被引用消息的文本与图片作为本轮**只读背景**；引用内容不会当成用户新说的话写进长期记忆，
  也不会被自动保存为当前上传文件。

## 已知限制

- 重复 Cron 提醒的预热只覆盖创建后首次触发。
- 模拟器模式的推送要通过 `/api/sim/replies` 取，没有真实 QQ 触达。
- 面板「日志」页与 `/metrics/history` 的趋势数据是**进程内的**，重启即清零，频繁部署时柱子很少是正常的。
- 备份与数据库在同一台机器同一块盘上，**只扛容器重装，扛不住整机故障**（异地备份还没做，见 `docs/todo.md`）。
