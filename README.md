# wechat-qq-agent

多用户 QQ 私聊长期陪伴 Agent 后端。Spring Boot 3 + LangChain4j + MySQL 8 + Redis + Quartz(JDBC)。
定位：长期陪伴型个人助手。核心优先级：**用户长期记忆不丢失 > 系统稳定运行 > 交互体验友好**。

## 技术栈

| 组件 | 选型 | 说明 |
|---|---|---|
| 框架 | Spring Boot 3.5 / Java 21 / Maven | |
| AI | LangChain4j 1.6 (core) + 自研 OpenAI 兼容 ChatModel | 任意兼容接口：DeepSeek / 通义 / 中转站 |
| 存储 | MySQL 8 + Spring Data JPA | 用户/记忆/提醒/日志/归档 |
| 缓存 | Redis | 即时对话上下文(10轮)、消息幂等去重 |
| 调度 | Quartz JDBC 持久化 | 提醒任务重启自动恢复 |
| 搜索 | SearX-NG（自托管） | JSON 格式，15s 超时，重试一次；配置见 docker/searxng/settings.yml |
| QQ | QQ 官方机器人 WebSocket | 默认私聊通道；每个 QQ 用户独立上下文、记忆与文件目录 |
| 微信 | 腾讯官方 iLink（wechat-ilink-sdk） | 可选兼容通道；本地模拟器仅作调试备用 |

## 快速开始

1. **启动基础设施**（Docker Desktop 需先启动）：
   ```bash
   cd wechat-agent-java
   docker compose up -d
   ```
   启动 MySQL(3306) / Redis(6379) / SearX-NG(8888)。
   SearX-NG 使用项目内配置（`docker/searxng/settings.yml`）：已开启 json 输出、关闭限流、并针对国内网络
   只启用可达引擎（Bing/百度/搜狗/360，禁用 Google/DDG/Wikipedia 等被墙引擎）。如你有自己的 SearX-NG
   实例，可在 `.env` 里设置 `SEARXNG_BASE_URL` 指向它。

2. **配置 LLM**（OpenAI 兼容接口，占位符可改）——三选一：
   - **推荐：复制项目根目录的 `.env.example` 为 `.env`**（`.env` 已 gitignore；Spring 通过 `spring.config.import` 自动加载）：
     ```
     LLM_API_KEY=你的key
     LLM_BASE_URL=https://api.deepseek.com/v1
     LLM_MODEL=deepseek-chat
     ```
   - 环境变量：`set LLM_API_KEY=你的key`
   - IDEA Run Configuration 的环境变量里设置
   不设置也能启动，但对话会报错。注意：系统环境变量的优先级高于 .env 文件；.env 里留空则视为未设置。

3. **启动**：
   ```bash
   mvn spring-boot:run
   ```

4. **启用 QQ 私聊机器人**：在 `.env` 设置 `QQ_ENABLED=true`、`QQ_APP_ID`、`QQ_CLIENT_SECRET`；首次联调建议保留 `QQ_SANDBOX=true`。`QQ_GROUP_ENABLED` 默认 `false`，当前不会处理群消息。

5. **开始对话**：直接在 QQ 私聊窗口向机器人发送消息。每个 QQ openid 都有独立的即时上下文、自动记忆、提醒和长期文件目录。

   > 默认不会启动微信兼容通道。调试备用：本地模拟器（不碰真实通道）需设置 `WECHAT_CHANNEL_MODE=simulator` 后重启，用 `POST /api/sim/send` 发消息测试记忆/提醒等功能。管理接口默认仅接受本机回环访问。

## 功能对照

| 需求 | 实现 |
|---|---|
| 每用户独立人设，新用户默认基础人设 | `UserService` + `user_profile` 表 |
| /set-prompt /reminders /memory /care /help（不走 LLM） | `CommandRegistry` + 独立 Handler |
| 不提供清空会话或长期记忆的快捷指令 | 对话连续保留；用户仅可通过 `/memory forget` 精确删除一条记忆 |
| 搜索工具（状态推送/去重/Top5/15s 超时/重试1次） | `SearchTool` + `SearxngClient` |
| 公开网页文件下载与发送 | `WebFileTool`：列出网页下载链接、限大小安全下载至用户目录、通过 QQ 富媒体接口发送 |
| 网页正文阅读 | `WebPageTool`，支持公开 HTTP/HTTPS 页面与受限跳转 |
| 提醒三件套（解析/列表/取消）+ 业务层落地 | `ReminderTool` + `ReminderService` + `ReminderParseService` |
| 默认提前 10 分钟预热 | 预热 Job 单独调度 |
| 过去时间拒绝 / 模糊时间反问 | 解析器返回 missing 项 → 反问 |
| Quartz JDBC 持久化，重启恢复 | `spring.quartz.job-store-type: jdbc` |
| 三层记忆（Redis 上下文 / 中期 / 核心置顶） | `ContextStore` / `UserWorkMemory` / `UserCoreMemory` |
| 3 秒静默窗口异步提取 | `MemoryExtractionScheduler` + `MemoryExtractor` |
| LLM 语义判重（>80% 不新增） | 提取提示词内完成 |
| 长期目标和稳定身份自动晋升核心记忆 | `MemoryExtractor` → `CoreMemoryService`，无需确认弹窗 |
| 用户明确修改已有事实时自动更新 | 核心/工作记忆更新均写变更日志，可审计回溯 |
| 记忆生命周期 | 工作记忆具备有效期、完成/过期状态、最后确认/使用时间与来源；核心长期目标不自动过期 |
| 文件与记忆关联 | 自动记忆保留来源消息和已保存资料 ID，可由 `/memory` 查看关联资料 |
| 用户自行核对和管理记忆 | `/memory` 确定性查询，不向 Agent 暴露存储查询工具 |
| 自然语言删除记忆 | `/memory forget 南京理工`，多条命中时要求指定编号，避免误删 |
| 超 20 条归档压缩（不删除、可回溯） | `MemoryArchiveService` + `memory_archive` |
| 所有记忆变更留痕 | `memory_change_log` |
| 每日全量备份 | `MemoryBackupJob`（backup/ 目录，保留 30 天） |
| 按用户范围日志隔离 | logback SiftingAppender → `logs/user/user-{hash}/`；不记录默认聊天正文 |
| 全局异常友好化 | `GlobalExceptionHandler` + 编排器兜底 |
| msg_id+user_id 幂等 | Redis SETNX 24h |
| 每用户串行处理 | `PerUserExecutors` |
| 连续附件/补充消息聚合 | `InboundMessageBatcher`：有最大时长的短窗口，仅合并连续媒体或明确补充，普通后续消息不会继承旧图片 |
| QQ 群聊 | 默认关闭（`QQ_GROUP_ENABLED=false`）；当前版本只保证 QQ 私聊链路 |
| PDF / DOCX 文件问答 | `DocumentExtractionService`，文本提取优先，扫描 PDF 交由多模态模型阅读 |
| 重要图片/文件长期保管 | Agent 自主判断价值并命名，按用户隔离存入 `stored-media/`，元数据写入 `stored_media` |
| 文件安全删除 | 必须先审阅内容并取得短期令牌，再移入当前用户 `.trash/`，不直接永久删除 |
| 低打扰主动关怀 | 默认关闭；用户通过 `/care daily|weekly|off` 自主选择目标复盘频率 |
| 工具状态降噪 | 每次用户请求最多推送一条“正在处理”状态，避免多工具连续刷屏 |

## 目录结构

```
src/main/java/com/liche/wechatagent
├── agent/        # 消息编排、对话循环、上下文、记忆加载
├── channel/      # 微信通道抽象 + 模拟器 + 幂等 + 重连策略
├── command/      # 斜杠指令解析器与处理器
├── config/       # LLM 配置、线程池
├── controller/   # 模拟器/健康 REST
├── exception/    # 全局异常
├── log/          # 操作日志
├── memory/       # 三层记忆 + 自动提取 + 用户管理 + 归档
├── media/        # 当前消息媒体授权、按用户长期存储、检索与审阅删除
├── reminder/     # 提醒实体/服务/Quartz Job
├── search/       # SearX-NG 客户端与搜索工具
├── tool/         # @Tool 注册表与工具
└── user/         # 用户档案（多租户）
```

## 配置（环境变量）

| 变量 | 默认 | 说明 |
|---|---|---|
| LLM_BASE_URL | https://api.deepseek.com/v1 | OpenAI 兼容地址 |
| LLM_MODEL | deepseek-chat | 模型名 |
| LLM_API_KEY | (空) | API Key |
| MYSQL_HOST/PORT/DB/USER/PASSWORD | localhost/3306/wechat_agent/root/root | MySQL |
| REDIS_HOST/PORT/PASSWORD | localhost/6379/空 | Redis |
| SEARXNG_BASE_URL | http://localhost:8888 | SearX-NG |
| DOCUMENT_MAX_FILE_BYTES | 20971520 | 单个 PDF/DOCX 最大字节数（20 MB） |
| DOCUMENT_MAX_TEXT_CHARS | 60000 | 注入模型的单文件最大文本长度 |
| DOCUMENT_MAX_PDF_PAGES | 10 | 扫描型 PDF 交给多模态模型的最大页数 |
| MEDIA_STORAGE_ROOT | stored-media | 重要图片/文件的长期存储根目录 |
| MEDIA_STORAGE_MAX_FILE_BYTES | 20971520 | 单个长期保存文件最大字节数（20 MB） |
| MEDIA_INSPECTION_TOKEN_MINUTES | 5 | 删除前审阅令牌有效分钟数 |
| AGENT_MESSAGE_BATCH_WINDOW_MILLIS | 1500 | 连续媒体/补充消息的聚合窗口；设为 0 可关闭 |
| AGENT_MESSAGE_BATCH_MAX_WINDOW_MILLIS | 4000 | 单个连续消息任务的最大聚合时长，避免旧附件一直滞留 |
| QQ_ENABLED / QQ_APP_ID / QQ_CLIENT_SECRET | false / 空 / 空 | QQ 私聊机器人开关与官方凭证 |
| QQ_SANDBOX | true | QQ 官方沙箱环境开关；生产机器人应设置为 false |
| QQ_GROUP_ENABLED | false | 群聊开关；默认关闭，当前版本不启用 |
| MEMORY_LIFECYCLE_SCAN_INTERVAL_MS | 3600000 | 工作记忆到期扫描间隔（毫秒） |
| CARE_SCAN_INTERVAL_MS | 60000 | 主动关怀到期扫描间隔（毫秒） |
| WEB_MAX_RESPONSE_BYTES | 2097152 | 单个网页最大响应字节数（2 MB） |
| WEB_MAX_TEXT_CHARS | 12000 | 交给模型的网页正文最大长度 |
| WECHAT_CHANNEL_MODE | disabled | 默认不启动微信通道；设 `clawbot` 或 `simulator` 才启用对应兼容通道 |
| BACKUP_DIR / 保留天数 | backup / 30 | 备份 |

## 微信接入（已接入：腾讯官方 iLink Bot API）

对接腾讯官方 iLink 协议（微信 ClawBot，合规通道），使用现成 Java SDK `io.github.lith0924:wechat-ilink-sdk`（已通过供应链安全审计：外连仅腾讯官方域名、无动态加载/进程执行、源码与字节码一致）。

**架构**：微信 → 腾讯 iLink 服务器（长轮询收 / sendmessage 发）→ SDK → `ClawBotChannel` → `AgentOrchestrator`（幂等/每用户串行/指令/记忆/LLM 全链路）。**无需额外进程**，就在 Spring Boot 内跑一个长轮询。

### 使用步骤

1. 启动应用并切换通道：
   ```bash
   java -jar target/wechat-agent-java-0.0.1-SNAPSHOT.jar --wechat.channel.mode=clawbot
   ```
2. **注册机器人**（多机器人：每个人一个专属助手，数据完全隔离）：
   ```bash
   curl -X POST http://localhost:8080/api/clawbot/register -H "Content-Type: application/json" -d '{"name":"我的助手"}'
   # 返回 qrcodeUrl（腾讯 liteapp 登录链接），渲染成二维码图片，用手机微信扫码
   ```
3. 扫码确认后，凭证自动保存到 `data/ilink-login-{name}.json`（已 gitignore），**重启免扫码自动恢复**。
4. 在微信机器人对话窗口发文字消息即可对话（**流式输出**，打字机效果）。

### 登录管理接口（clawbot 模式下）

| 接口 | 说明 |
|---|---|
| POST /api/clawbot/register {name} | 注册新机器人并返回登录二维码（多机器人用不同 name） |
| GET /api/clawbot/bots | 列出所有机器人及登录状态（name/loggedIn/botId） |
| POST /api/clawbot/logout?name=xxx | 清除指定机器人登录凭证并断开 |

### 说明与限制（一期）

- **多机器人隔离**：每个 name 注册的机器人独立登录凭证（`data/ilink-login-{name}.json`）、独立长轮询；消息按微信 `from_user_id` 做行级多租户隔离（人设/记忆/提醒/日志/上下文全部互不干扰）
- **流式输出**：对话回复走 SSE 流式 + 按句子自然边界分片推送（打字机效果）；模型配置见 `llm.model`（.env）
- 仅支持**私聊文本**收发；图片/语音/文件等媒体消息会收到"暂时只支持文字"提示（SDK 已支持媒体，二期可扩展）
- 消息映射：微信 `from_user_id` → 多租户 `userId`；`message_id` → `msg_id`（幂等去重）
- 断线/心跳/重试由 SDK 内置（长轮询 + 指数退避 + 心跳），心跳间隔可配 `wechat.clawbot.heartbeat-interval-ms`（默认 3000ms）

## 用户标识约定（多租户核心，多通道通用）

- **userId**：由网关层（`WeChatChannel` 实现）从平台消息中提取的**唯一且不变**的用户身份——微信为 openid、QQ 为 QQ 号/openid。**全系统所有数据隔离（人设/记忆/提醒/幂等/上下文/日志）一律按 userId**。
- **botId**：仅用于回复路由（该用户消息来自哪个机器人、回给哪个机器人），不参与数据隔离。
- **msgId**：平台消息 id，与 userId 组成幂等键。
- 新增通道（如 QQ）时：在通道实现里把平台用户标识映射为 userId 即可，下游零改动。

## QQ 群聊

当前默认关闭群聊（`QQ_GROUP_ENABLED=false`）。个人开发者账号的群聊能力受 QQ 开放平台资格限制，本仓库当前的发布与回归测试范围仅包含 QQ 私聊；不要把群聊当作已上线功能。

## QQ 文件识别

- 在 QQ 私聊中发送 PDF、DOCX 后直接提出问题即可；机器人会下载、校验真实格式并读取文件。
- 文本型 PDF 与 DOCX 直接提取文字；没有可用文字的扫描型 PDF 会将前若干页转为图片，交给配置的多模态模型识别。
- 普通临时文件只用于当前次模型请求；若 Agent 判断为课表、证书、长期项目资料等未来仍有价值的内容，会自动按内容命名并保存。表情包、随手截图、重复和一次性资料不得保存。
- 长期文件存入 `stored-media/{用户隔离目录}/active/`，数据库只保存检索元数据和相对路径。Agent 无法指定磁盘路径或用户 ID，也不能保存当前消息之外的任意文件。
- 用户明确要求下载公开文件时，Agent 可先从网页中列出直接下载链接，再下载指定文件到该用户目录；不会绕过登录、付费墙、访问控制或版权限制。下载后如用户明确要求，可将该用户自己的文件通过 QQ 富媒体消息发送。
- 删除前必须先读取文件摘要和提取内容，取得一次性短期令牌；删除操作只会移动到该用户的 `.trash/`，不会直接永久擦除。
- Agent 只有在用户当前消息明确要求删除时才能调用删除；询问“怎么删除”或明确说“不要删除”都不会获得删除授权。
- 文件列表和审阅结果包含保存时间、更新时间与内容摘要；同名新版本不会静默覆盖旧版。
- Agent 可以按文件名或资料内容检索已保存文件，并将已保存图片重新交给多模态模型查看，或读取已保存 PDF/DOCX 的提取文本。

## QQ 引用消息

- QQ 私聊中回复/引用一条消息时，机器人会读取被引用消息的文本和图片，并将其作为本轮回答的只读背景。
- 网关事件未直接带引用正文时，机器人会按当前 QQ 用户和被引用消息 ID 通过 QQ 官方接口回取；接口短暂不可用时才退回本机有时限、容量受控的短期缓存。
- 引用内容不会被当作当前用户新说的话写进长期记忆，也不会被自动保存为当前上传文件。

## 主动关怀

- 默认关闭，不会未经允许主动打扰。
- `/care on` 或 `/care weekly`：每周日晚约 20:30 围绕长期目标做一次简短复盘。
- `/care daily`：每天约 20:30 复盘；`/care off` 随时关闭。
- 仅发送给 QQ 私聊用户。没有可用长期目标时会静默跳过，不发送空泛消息。
- 当前只支持未加密的 PDF、DOCX；扫描 PDF 页数受 `DOCUMENT_MAX_PDF_PAGES` 限制。

## 已知限制（一期）

- 重复 Cron 提醒的预热目前只覆盖创建后首次触发；
- 模拟器模式（调试用）的推送通过 `/api/sim/replies` 查询获取，无真实 QQ 触达。
