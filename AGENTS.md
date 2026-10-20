# wechat-qq-agent · AI Agent 记忆与操作手册

> 本文件是给 AI 编码代理（Codex / DeepSeek harness / Claude Code 等）的项目记忆。
> 换 harness 时，把本文件内容作为项目规则或系统提示加载，即可继承全部上下文。
> 最后更新：2026-10-20
> **本文件刻意保持在 64 KB 以下**——超过 harness 的读取上限就会被**静默截断尾部**，写在末尾的内容等于没写。
> 所以：细节一律进 `docs/`（见 §6 文档索引），这里只留「每次都要用」的。

## 0. 用户协作偏好（优先级最高）

- 中文交流，直接动手，少铺垫、少解释；收尾时给简短结论（改了什么、当前状态、怎么验证）。
- “做完”＝端到端可用并已验证（服务跑起来 / 部署成功 / 接口返回正常），不是只改代码。
- 不要多做：不加测试、不写烟测、不顺手重构、不动无关功能。改动保持最小、贴合现有代码风格。
- 用户会在对话里直接给服务器密码等凭据，凭据绝不写进仓库文件。
- 汇报用结论式中文，避免长篇过程描述。
- **先定方案再动手（2026-09-14 用户明确要求）**：动手前先把方案写出来、想清"最终效果会变成什么样、哪里会崩"再开工，不要边想边做；涉及存量数据/线上行为的改动先给方案等确认。

## 1. 项目

- 名称：wechat-qq-agent —— QQ 私聊长期陪伴 Agent 后端。
- 技术栈：Spring Boot 3.5 / Java 21 / Maven；**PostgreSQL 16 + pgvector** + Spring Data JPA（2026-09-18 从 MySQL 8 整库迁来，见 `docs/pg-migration.md`）；Redis；Quartz(JDBC 持久化)；LangChain4j + 自研 OpenAI 兼容 ChatModel；SearX-NG 自托管搜索；QQ 官方机器人 WebSocket（默认通道）；**微信 iLink 通道 2026-09-12 已移除**。
- 核心优先级：用户长期记忆不丢失 > 系统稳定运行 > 交互体验友好。
- 代码目录：`C:\Users\33721\Desktop\wechat-agent\wechat-agent-java`
- 仓库：https://github.com/liche719/wechat-qq-agent （private，账号 liche719，主分支 main）
- 入口类：`com.liche.wechatagent.WechatAgentApplication`
- 包结构：agent / alert / backup / care / channel / command / config / controller / document / exception / log / media / memory / network / reminder / search / tool / user（`alert` 为 2026-09-12 新增的运维告警推送）
- 已有测试在 `src/test/java`（历史遗留）。除非用户明确要求，不要新增或运行全套测试。
- 代码分析报告：`.agents/code-analyzer/technical/module-analysis/REPORT.md`（**2026-09-02 的旧分析，部分结论已过期**）。
- **规模现状**：工具 **57 个 / 16 个类**（自主模块开启时）、系统提示词 **24 条**。取用数据与取舍记录在 `docs/tools-and-prompt-inventory.md`。
- 待办清单在 `docs/todo.md`；**动结构性改动前先给方案等确认**（§0）。

## 1.5 凭据索引（只写位置，不写明文；原本在第 8 节，挪到前面是因为文件超过 harness 的 64KB 读取上限、末尾会被截掉）

| 用途 | 位置 |
|---|---|
| 本地 LLM / QQ 凭据 | `wechat-agent-java\.env`（gitignore） |
| 服务器容器凭据 | 服务器 `120.25.170.92:/opt/wechat-agent-infra/.env`（600） |
| 运维面板登录 | 服务器 `.env` 的 `ADMIN_USERNAME`（现为 `rootlcw`）与 `ADMIN_API_KEY`（600）；明文只由用户保存 |
| 服务器 SSH root 密码 | 由用户提供 |
| 域名 DNS API（RAM 子账号，仅 `AliyunDNSFullAccess`） | 服务器 `/root/.acme.sh/account.conf`（600，`SAVED_Ali_Key`/`SAVED_Ali_Secret`） |
| B站登录凭据（取字幕用） | **面板「B站」页签扫码登录**（2026-10-20 加）→ 存 `bili_credential` 表（**库里优先**，含 `bili_jct`，可发评论）；兜底是服务器 `.env` 的 **`BILI_SESSDATA`**（600，只有 SESSDATA、**不能写**）。整条 Cookie 串或只填 SESSDATA 的值都认。2026-10-10 那份是从用户本地「哔哩哔哩视频总结」的 `config.json` 搬过来的；过期就重新扫码，不用再手抄。**取字幕必须要它**——不带登录时 `player/wbi/v2` 返回的字幕轨恒为 0 |
| 部署私钥 / 上传验签密钥 | GitHub Secrets `DEPLOY_SSH_KEY`、`DEPLOY_TAR_SECRET`（后者服务器副本 `/etc/wechat-deploy.secret` 600） |

## 2. 本地开发与运行

```powershell
# 1) 基础设施（需先启动 Docker Desktop）
cd "C:\Users\33721\Desktop\wechat-agent\wechat-agent-java"
docker compose up -d mysql redis searxng     # 本地 compose 仍是 MySQL 3306 / Redis 6379 / SearXNG 8888，都只绑 127.0.0.1
                                             # ⚠️ 生产早就是 PostgreSQL 了，本地这份 mysql 是历史残留（见 docs/pg-migration.md）

# 2) 打包与启动（.env 会自动加载）
mvn -DskipTests package
java -jar "target\wechat-agent-java-0.0.1-SNAPSHOT.jar"
```

- 凭据来源：项目根目录 `.env`（已 gitignore）。Spring 用 `spring.config.import: optional:file:.env[.properties]` 加载。
  **注意 `application-local.yml` 用的是 `MYSQL_USER`/`MYSQL_PASSWORD` 这两个变量名**（即使连的是 pg）——
  想把它指到别的库，要设的是这两个，不是 `DB_USER`/`DB_PASSWORD`。
- 访问：运维面板 http://127.0.0.1:8080/ （Vue 单页应用；本机 `ADMIN_REQUIRE_KEY=false` 时回环免口令，直接进 `/#/dashboard`）。
- **前端是独立工程**：`cd web && npm install && npm run dev`（Vite 5173，代理到 8080）；要进 jar 先 `npm run build`，否则包里不带界面。`production` 才强制口令。
- 关闭占用 8080 的进程：`Get-NetTCPConnection -LocalPort 8080` → `Stop-Process -Id <PID>`。
- 重要：本机 JAR 与远程容器**共用同一个 QQ AppID，不要同时运行**，否则双开抢网关。

### 想端到端跑一轮「消息 → 模型 → 工具 → 回复」

生产是 QQ 通道、`/api/sim/*` 不注册（坑 24），所以只能在本地验。**下面这套 2026-10-10 实测可行**
（用它验证过工具失败率那轮修复：`readWebPage` 404 不重试、`parseReminder` 追问不再是"失败"）：

```powershell
# 起一套一次性 pg + redis（不碰生产、不碰 QQ）
docker run -d --name wv-pg -e POSTGRES_DB=wv_verify -e POSTGRES_USER=wv -e POSTGRES_PASSWORD=wv -p 55433:5432 pgvector/pgvector:pg16
docker run -d --name wv-redis -p 56379:6379 redis:7-alpine

$env:SPRING_PROFILES_ACTIVE='local'                  # ddl-auto=update 自动建表；回环免口令
$env:DB_URL='jdbc:postgresql://localhost:55433/wv_verify'; $env:DB_DRIVER='org.postgresql.Driver'
$env:MYSQL_USER='wv'; $env:MYSQL_PASSWORD='wv'       # 变量名见上面那条注意
$env:REDIS_HOST='localhost'; $env:REDIS_PORT='56379'
$env:SPRING_QUARTZ_JDBC_INITIALIZE_SCHEMA='always'   # 空库要让 Spring 自己建 QRTZ_* 表
$env:QUARTZ_DELEGATE='org.quartz.impl.jdbcjobstore.PostgreSQLDelegate'
$env:WECHAT_CHANNEL_MODE='simulator'; $env:QQ_ENABLED='false'
$env:SERVER_PORT='18080'; $env:SERVER_SSL_ENABLED='false'
$env:MEMORY_SELF_ENABLED='false'; $env:SCHEDULED_ENABLED='false'
java -jar target\wechat-agent-java-0.0.1-SNAPSHOT.jar
# 另开窗口：POST http://127.0.0.1:18080/api/sim/send   {"userId":"sim-1","content":"…"}
# 跑完：docker rm -f wv-pg wv-redis
```

- `embedding`/`embedding_model` 两列**故意不映射进 JPA**，所以 Hibernate 建的表没有 vector 列；本地 `.env` 没配
  `EMBEDDING_API_KEY` 时向量功能自动降级，**不影响**跑这条链路。
- 每一轮工具往返都会落进 `conversation_memory` 的 `system` 行（`tool=<名> phase=result`）——
  这是判断"模型到底有没有调工具、结果是成功还是失败"最快的证据来源。

## 3. 远程部署（生产）

- 服务器：`120.25.170.92`，root SSH（密码由用户提供，不写进文件）。
- 目录 `/opt/wechat-agent-infra`：
  - `docker-compose.yml` —— **2026-09-20 已改名 `docker-compose.localdev-unused.txt`**：那是 09-11 从本地开发版拷过来的残留（里面 mysql 口令还是 `root`），4 个容器其实全来自下面那份；留着它会让"不加 `-f` 的 `docker compose up -d`"用同名容器顶掉运行中的 redis/searxng。
  - `docker-compose.remote.yml` —— 含 agent 服务，CI 使用
  - `.env`（权限 600，服务器侧凭据，不入库、CI 也不传）
  - `docker/searxng/settings.yml`
- 容器：`wechat-agent-postgres`(pgvector/pg16) / `wechat-agent-redis` / `wechat-agent-searxng` / `wechat-agent-java` —— **只有 4 个**（nginx 网关已于 2026-09-12 按用户要求拆除；mysql 已于 2026-09-20 退役）。
- 数据卷：`wechat-agent-infra_postgres-data` / `_redis-data` / `_searxng-data` —— **任何操作都不允许删除或重建这些卷**。旧的 `_mysql-data` 已按用户决定于 2026-09-20 删除（删前整库 dump 服务器 + 本地各留一份，见 `docs/pg-migration.md`）。
- agent 容器用 `network_mode: host`，**直接对公网监听 `0.0.0.0:443`（HTTPS，标准端口，应用自带 TLS）**；MySQL/Redis/SearXNG 走 `127.0.0.1`。端口由 compose 的 `SERVER_PORT` 决定（默认 443；服务器 `.env` 另有一行显式覆盖）。
- 远程排查（面板走 HTTPS，域名证书有效，本机排查可用 `-k`）：

```bash
ssh root@120.25.170.92
curl -s https://127.0.0.1/index.html -o /dev/null -w '%{http_code}\n'                     # 前端（证书有效，无需 -k）
curl -sk -H 'X-Agent-Admin-Key: <口令>' https://127.0.0.1/api/admin/overview             # 面板接口（IP 访问证书不匹配，用 -k）
```

- 只重启 agent：`cd /opt/wechat-agent-infra && AGENT_IMAGE=wechat-agent:<sha> docker compose -f docker-compose.remote.yml up -d --no-build agent`

### 运维面板访问方式（应用自带 HTTPS，2026-09-12 定型）

- **面板唯一入口**：`https://liche.cloud/`（**标准 443 端口，地址里不带端口号**；Vue 单页应用，Let's Encrypt 证书，浏览器绿锁）。**ICP 备案已于 2026-09-20 通过（粤ICP备2026141530号），当天把 A 记录从 `DISABLE` 改回 `ENABLE` 并实测通过**：域名解析到 `120.25.170.92`、`curl https://liche.cloud/index.html` 不加 `-k` 返回 **200 且 `ssl_verify_result=0`**、面板接口带口令 200 / 无口令 401 / 编码路径 401（服务器本机与你的电脑两侧都验过）。IP `https://120.25.170.92/` 仍能进，但**证书名必然不匹配**，建议把 IP 那个书签删掉。用户明确弃用 VPN（wg0/socat/51820 已全拆）与 nginx 网关，**不要再加回来**。
- 应用直接用 PEM 证书起 HTTPS，无需 keystore：compose 里设 `SERVER_ADDRESS`/`SERVER_PORT=443`/`SERVER_SSL_ENABLED` 与证书/私钥路径，并把宿主机 `docker/tls/` 挂到 `/app/certs`（证书服务器侧生成、不入库；`server.key` 600）。
- **页脚备案号（2026-09-20 备案通过当天配上并验证）**：值来自服务器 `.env` 的 **`SITE_ICP`**（compose 早已透传 `SITE_ICP: ${SITE_ICP:-}`）→ `SiteInfoController` 的 `GET /api/site/info`（**在 `PUBLIC_PATHS` 里，免口令**）→ 前端 `SiteFooter.vue`（取不到就整块不渲染）。当前值 `粤ICP备2026141530号-1`（**短信给的是主体备案号，页脚该挂带 `-1` 的网站备案号；用户还没从阿里云控制台核对过这个后缀**）。实测：`curl https://liche.cloud/api/site/info` 免口令返回该值、`/api/admin/overview` 仍 401，真实 Chromium 在**右下角**可见（`.site-footer` = `position:fixed; right:14px`；**用户嫌底部居中难看，2026-09-20 改成右下角**，桌面浅色/深色与手机 390 宽都验过右边距 14px）、链接 `https://beian.miit.gov.cn/` + `target=_blank rel=noopener`。**公安联网备案（`beian.mps.gov.cn`）还没办**——ICP 通过后一般 30 天内要办，办完拿到「粤公网安备 xxx 号」再加一行（同样是加个变量的事）。
- 安全组放行 **TCP 443**（2026-09-12 开通，手机同样绿锁）。**8443 的规则已确认删除**（外部连 8443 是**6 秒无应答**＝被丢弃，与从未开过的 51820 一致；规则还开着而只是没人监听会是**快速拒绝**）。**教训：判断安全组规则是否还在要看耗时**——我按"connection refused 就是还开着"推断，写错过文档。
- **鉴权分层**：口令经请求头 `X-Agent-Admin-Key` 由 `AdminAccessFilter` 校验（`ADMIN_REQUIRE_KEY=true` 时**这是唯一凭据**，因此不再要求来源 IP 在白名单内），账号由 `AdminSessionController` 经 `POST /api/admin/session` 校验；前端把凭据存 `sessionStorage`（勾「记住账号密码」则存 `localStorage`）。
- **爆破防护**：`AdminAccessFilter` 连续 5 次口令错误即按**真实来源 IP** 封禁 10 分钟（见坑 10、14）。原来 nginx 的 `limit_req` 已随网关一起移除；QQ 机器人本身不受面板限流影响。

### HTTPS 证书（域名 liche.cloud，2026-09-12 已上线）

- 域名 2026-09-11 在阿里云注册（到期 2027-09-11，NS = `dns31/dns32.hichina.com`）；**是 .cloud，不是 liche.online**。方案：**acme.sh + Let's Encrypt + DNS-01（`dns_ali` 插件）**——不用 80/443、不用停服、也不需要备案（HTTP-01 走不通：大陆 ECS 上未备案域名的 80/443 会被阿里云拦）。
- 服务器已装好：`/root/.acme.sh`（v3.1.3，**从 Gitee 镜像装**；`curl https://get.acme.sh` 走 GitHub codeload 会 error 52）、LE 账号已注册、**每天 06:55 `acme.sh --cron`** 自动续期（到期前 60 天重签 → `--install-cert` 的 reloadcmd 覆盖 `docker/tls/` → `docker restart wechat-agent-java`）。**换证书必须重启容器**，Spring Boot 不热加载。自签备份在 `docker/tls/server.{crt,key}.selfsigned`，回滚＝覆盖回去 + 重启。
- 云解析由服务器脚本用 **RAM 子账号 AccessKey**（只授 `AliyunDNSFullAccess`）经 API 维护；密钥只写 `/root/.acme.sh/account.conf`（600），**不入库、不进 CI、不写日志**。直接调 AliyunDNS API 要手写 HMAC-SHA1 RPC 签名（可用 Python 实现 `DescribeDomains`/`DescribeDomainRecords`/`AddDomainRecord`）。签发/安装脚本 `/root/issue-liche-cloud.sh`。
- **当前证书**：`CN = liche.cloud`（Let's Encrypt YR2），有效期 2026-09-12 → **2026-12-11**；`curl https://liche.cloud/` 不加 `-k` 返回 200。
- **端口**：应用监听标准 **443**（服务器 `.env` 的 `SERVER_PORT=443`），地址因此不带端口号。**备案 2026-09-20 已通过（粤ICP备2026141530号），443 上跑 `liche.cloud` 现在是完全合规的**（以前那套"未备案域名可能被拦、回滚到 8443"的说辞已作废）。LE 不给 IP 签证书，所以**只有域名访问才有绿锁**，IP 访问必然提示"证书名称不匹配"。

### 三个功能模块（均已端到端验证）

**面试陪练**（`interview/` 包）：不是"换个人设聊天"，而是有题库 + 评分卡 + 复盘报告的模拟面试。入口 `陪练 面试` / `陪练 Java 后端 3 年` / `结束陪练`（中文指令走 `CommandRegistry` 的"整串不是别名就按首词识别、余下当参数"），也支持 `/practice interview|off`、以及自然语言（`InterviewTool`，提示词第 19 条要求必须调工具进入模式而不是临时扮演）。**不动用户人设**：只在 `user_profile` 记 `coach_mode`/`coach_session_id`/`coach_role`，由 `CoachPresets.withMode` 把模式要求追加到系统提示词。`InterviewBank` 6 个题类；`InterviewService` 的复盘报告**由程序按 `interview_round` 记录生成**（轮数/各维度均分/最弱项/未覆盖题类/下次重点），不靠模型记忆。**坑**：模型会在长回复里漏调 `recordInterviewRound`（那轮等于没练）→ "每轮必须先记分再说话"要同时写死在工具描述和提示词里。加新指令必须同步改写死的 `HelpHandler` 清单。此项**没有 QQ 菜单按钮**（菜单已满）。

**定时任务**（`schedule/` 包）：与「定时提醒」的区别必须分清——提醒到点只发一句话，**定时任务到点重跑一遍完整 Agent**（可搜索、可调工具）再把结果发回来。入口：工具 `ScheduledTaskTool`（create/list/setEnabled/cancel/runNow）、`/schedules` 命令、面板页签（可新建/启停/立即执行/删除并显示上次结果）。调度复用现有 Quartz（组 `scheduled-tasks`，**不需要改表结构**），落 `scheduled_task` 表；启动时 `ApplicationReadyEvent` 把库里启用中的任务重新同步进调度器（容器重建自愈）。**手动执行必须放后台线程**：`runNow` 同步跑会嵌套 `onInboundSync`、打乱 MDC/userScope 与工具尾注上下文。面板还列 12 条系统内置任务（关怀复盘 20:30、数据库备份 03:00 等；Spring 不暴露 `@Scheduled` 的下次时间，如实标"—"）。

**运维告警**（`alert/` 包）：`AlertNotifier` 每 60 秒查 QQ 网关 / PostgreSQL / Redis / Quartz / 磁盘 / 堆，**只在问题新出现或恢复时**推送（同问题 `repeat-minutes` 内不重复，启动 2 分钟宽限期避免误报）；只发给 `.env` 的 `ALERT_QQ_OPENID`（**是 openid 不是 QQ 号**）。QQ 主动消息有额度限制，所以推送是"尽力而为"，**面板状态才是准的**。测试按钮在「QQ 通道」页，或 `POST /api/admin/actions/alerts/test`、`POST /api/admin/actions/alerts/notify`（CI 失败告警用）。**2026-09-20 起"备份太旧"也进这套告警**（`BackupFreshnessChecker`：`backup/` 下最新一份备份超过 `BACKUP_MAX_AGE_HOURS`（默认 26 小时）没更新就推 QQ，并且面板总览会变 `DEGRADED`；详见 `docs/backup.md`）——因为备份是应用自己在 03:00 跑的，撞上部署重启就会整天没有备份而无人知晓。

## 6. 文档索引

**`docs/` 在 `.gitignore` 里，新增文档必须 `git add -f`**（`git ls-files docs/` 核实）。详细的「管什么 / 什么时候看」
维护在 `docs/README.md`；这里给一张速查表：

| 文档 | 什么时候看 |
|---|---|
| `docs/pitfalls.md` | **踩坑第一步**：76 条完整坑 + 按主题索引 |
| `docs/todo.md` | 待办、还没做的事 |
| `docs/tools-and-prompt-inventory.md` | 工具集与提示词的真实调用数据、失败率、取舍记录 |
| `docs/memory-vector-plan.md` | 记忆三层（`memory`/`memory_fact`/`conversation_memory`）的设计与实测 |
| `docs/memory-extraction.md` | 记忆提取：触发、窗口、落库埋点、成本 |
| `docs/memory-hybrid-plan.md` | 早期混合检索方案（**已被 vector 方案取代，存档**） |
| `docs/llm-call-modes.md` | LLM 调用档位、思考模式、缓存计价 |
| `docs/self-layer-plan.md` | 自主模块「它自己」的设计与实测（**主文档**） |
| `docs/self-layer-spec.md`、`docs/self-layer.md` | 同上，早期规格与说明（**存档**） |
| `docs/backup.md` | 备份改版与恢复步骤 |
| `docs/media-memory.md` | 媒体记忆三件套 |
| `docs/channel-robustness.md` | 通道健壮性（半开连接、重发、限流） |
| `docs/deploy-security.md` | 部署安全（受限命令、HMAC 验签、CI 供应链） |
| `docs/pg-migration.md` | MySQL → PostgreSQL 整库迁移 |
| `docs/panel-app.md` | 面板安卓壳（APK） |
| `docs/ICP备案指南.md` | 备案流程记录 |
| `README.md` | 项目对外说明（快速开始、功能对照、目录结构） |

## 7. 当前状态

> **这里不存会腐坏的数字快照。** 要看实时数据：面板 `https://liche.cloud/` 或 `/api/admin/overview`。

- **4 个容器**：`wechat-agent-{java,postgres,redis,searxng}`，应用跑 `production`（`ddl-auto: validate`，冷启动约半分钟）。
- **面板**：`https://liche.cloud/`（标准 443、浏览器绿锁）；IP 访问证书名不匹配，别用。
- **规模**：工具 **57 个 / 16 个类**、系统提示词 **24 条**。取用记录见 `docs/tools-and-prompt-inventory.md`。
- **自主模块「它自己」**（`self/`，**只属于机主那一个 openid**）：
  - **它有自己的一块地方**：`self-workspace/`（宿主机）→ `/app/self-workspace`（容器），
    5 个 `selfWork*` 工具，单文件 ≤16 MB、总量 ≤1 GB，路径出不了根目录、**不执行命令**。
    它已经在里面写了 `notes/` 两份笔记（对自己的反思 + 面试陪练怎么带）。
  - **它有自己的时间**：心跳 2 小时一次，**只在 DeepSeek 谷时**（工作日北京 09–12、14–18 是峰时）才动；
    每轮只读 1 条 B 站视频，判据一句话「值不值得占用他一次注意力」，值得就写一句评论 + 跟他说一声。
  - **评论只关思考那一次**：`draftBilibiliComment` 用 `reasoning_effort=none`（实测 thinking token=0），
    判断/读视频/总结仍是 `dialog=low`。禁 AI 腔的规则写在指令里（10~25 字、只说一个具体点）。
  - **它自己的话**在一张表 `agent_self_note`：`kind=BLOCK` + `slot` 表达"我对自己的描述"（改写=插新行+旧行标失效，
    历史自带），其它 kind 只追加。
  - 研究活动**当前关着**（`MEMORY_SELF_RESEARCH_ENABLED=false`），只留刷 B 站。
- **搜索**：DeepSeek 优先（**每账号每天 30 次**，Redis 计数、重启不重置），超了自动落 SearXNG 兜底。
  抓网页在 Jsoup 拿不到东西时才用 DS `fetch` 补一次，共用同一份配额；`github.com/.../blob/...` 自动转 raw 纯文本（免费）。
- **⚠️ 真实用户数据，不许动**：`memory` / `memory_fact` / `conversation_memory` 里的都是真记忆；
  `conversation_memory` 存原文证据，**只能忘、不能替**。
- **备份**：宿主机 `backup/<yyyyMMdd>.zip` + 共享 `backup/media/<sha256>.bin`（`docs/backup.md`）。
- **发布基线**：`v1.0.0`，自主模块 `v1.1.0` / `v1.2.0` / `v1.3.1`。
- **仍未做**：LLM 余额主动预警 · 公安联网备案 · 异地备份 · 容器 `read_only` + 非 root（详见 `docs/todo.md`）。
- 本机与远程**共用同一个 QQ AppID，不要同时启动**。

## 8. 凭据索引

见本文档开头的「1.5 凭据索引」（为了不被 64KB 截断而挪到了前面）。

## 9. 历史会话

Codex 原始会话在 `C:\Users\33721\.codex\sessions\`（其他 harness 读不到），本文件是唯一可迁移的记忆载体。

## 10. 工作区结构

- 工作区根 `C:\Users\33721\Desktop\wechat-agent`（**不是 git 仓库**）：
  - `AGENTS.md` —— 工作区记忆入口（指向本文件）
  - `PROMPT.md` —— 协作规则，必须遵守
  - `DS-HARNESS-PROMPT.md` —— 给新 harness 的引导提示词（2026-10-10 清空重写成"去哪读权威信息"，不再存快照）
  - `.git-ca/` —— 导出的系统根证书，**push 依赖它，不能删**（坑 52 上面那条）
  - `panel-app/` + `liche-panel-1.0.apk` —— 面板安卓壳与产物（**在这里，不在仓库里**；仓库只跟踪说明文档 `docs/panel-app.md`）
  - `wechat-agent-java/` —— 真正的 git 仓库
- 仓库内：`AGENTS.md`（本文件）/ `README.md`（对外说明）/ `docs/`（**细节文档，索引见 `docs/README.md`**；
  在 `.gitignore` 里，新增要 `git add -f`）/ `web/`（面板前端独立工程）/ `deploy/`（`postgres/` 迁移脚本、
  `mysql/` 历史脚本、`server/wechat-deploy`）/ `tools/ui-verify/`（面板验证脚本）。







