# wechat-qq-agent · AI Agent 记忆与操作手册

> 本文件是给 AI 编码代理（Codex / DeepSeek harness / Claude Code 等）的项目记忆。
> 换 harness 时，把本文件内容作为项目规则或系统提示加载，即可继承全部上下文。
> 最后更新：2026-09-11（由 Codex 会话沉淀）

## 0. 用户协作偏好（优先级最高）

- 中文交流，直接动手，少铺垫、少解释；收尾时给简短结论（改了什么、当前状态、怎么验证）。
- “做完”＝端到端可用并已验证（服务跑起来 / 部署成功 / 接口返回正常），不是只改代码。
- 不要多做：不加测试、不写烟测、不顺手重构、不动无关功能。改动保持最小、贴合现有代码风格。
- 用户会在对话里直接给服务器密码等凭据，凭据绝不写进仓库文件。
- 汇报用结论式中文，避免长篇过程描述。

## 1. 项目

- 名称：wechat-qq-agent —— QQ 私聊长期陪伴 Agent 后端。
- 技术栈：Spring Boot 3.5 / Java 21 / Maven；MySQL 8 + Spring Data JPA；Redis；Quartz(JDBC 持久化)；LangChain4j + 自研 OpenAI 兼容 ChatModel；SearX-NG 自托管搜索；QQ 官方机器人 WebSocket（默认通道），微信 iLink 为可选通道。
- 核心优先级：用户长期记忆不丢失 > 系统稳定运行 > 交互体验友好。
- 代码目录：`C:\Users\33721\Desktop\wechat-agent\wechat-agent-java`
- 仓库：https://github.com/liche719/wechat-qq-agent （private，账号 liche719，主分支 main）
- 入口类：`com.liche.wechatagent.WechatAgentApplication`
- 包结构：agent / alert / backup / care / channel / command / config / controller / document / exception / log / media / memory / network / reminder / search / tool / user（`alert` 为 2026-09-12 新增的运维告警推送）
- 已有测试在 `src/test/java`（历史遗留）。除非用户明确要求，不要新增或运行全套测试。
- 代码分析报告：`.agents/code-analyzer/technical/module-analysis/REPORT.md`

## 2. 本地开发与运行

```powershell
# 1) 基础设施（需先启动 Docker Desktop）
cd "C:\Users\33721\Desktop\wechat-agent\wechat-agent-java"
docker compose up -d mysql redis searxng     # MySQL 3306 / Redis 6379 / SearXNG 8888，均只绑 127.0.0.1

# 2) 打包与启动（.env 会自动加载）
mvn -DskipTests package
java -jar "target\wechat-agent-java-0.0.1-SNAPSHOT.jar"
```

- 凭据来源：项目根目录 `.env`（已 gitignore）。Spring 用 `spring.config.import: optional:file:.env[.properties]` 加载。
- 不再需要 `--spring.quartz.jdbc.initialize-schema=never` 参数，已固化在 `application-local.yml`。
- 访问：运维面板 http://127.0.0.1:8080/ （Vue 单页应用；本机 `ADMIN_REQUIRE_KEY=false` 时回环免口令，直接进 `/#/dashboard`）
- **前端是独立工程**：`cd web && npm install && npm run dev`（Vite 5173，接口代理到 8080）最方便；要进 jar 就先 `npm run build`，否则 `mvn package` 出来的包不带界面。
- 管理接口默认不要求密钥（`ADMIN_REQUIRE_KEY=false`，回环地址免密钥）；`production` profile 才强制密钥。
- 关闭占用 8080 的进程：`Get-NetTCPConnection -LocalPort 8080` → `Stop-Process -Id <PID>`
- 重要：本机 JAR 与远程容器使用同一个 QQ AppID，**不要同时运行**，否则双开抢网关。

## 3. 远程部署（生产）

- 服务器：`120.25.170.92`，root SSH（密码由用户提供，不写进文件）。
- 目录 `/opt/wechat-agent-infra`：
  - `docker-compose.yml` —— 基础设施三件套
  - `docker-compose.remote.yml` —— 含 agent 服务，CI 使用
  - `.env`（权限 600，服务器侧凭据，不入库、CI 也不传）
  - `docker/searxng/settings.yml`
- 容器：`wechat-agent-mysql`(mysql:8.0.46) / `wechat-agent-redis` / `wechat-agent-searxng` / `wechat-agent-java` —— **只有 4 个**（nginx 网关已于 2026-09-12 按用户要求拆除）。
- 数据卷：`wechat-agent-infra_mysql-data` / `_redis-data` / `_searxng-data` —— **任何操作都不允许删除或重建这些卷**。
- agent 容器用 `network_mode: host`，**直接对公网监听 `0.0.0.0:8443`（HTTPS，应用自带 TLS）**；MySQL/Redis/SearXNG 走 `127.0.0.1`。
- 远程排查（面板走 HTTPS，注意 `-k`）：

```bash
ssh root@120.25.170.92
curl -sk https://127.0.0.1:8443/index.html -o /dev/null -w '%{http_code}\n'          # 前端
curl -sk -H 'X-Agent-Admin-Key: <口令>' https://127.0.0.1:8443/api/admin/overview    # 面板接口
```

- 只重启 agent：`cd /opt/wechat-agent-infra && AGENT_IMAGE=wechat-agent:<sha> docker compose -f docker-compose.remote.yml up -d --no-build agent`

### 运维面板访问方式（应用自带 HTTPS，2026-09-12 定型）

- **面板唯一入口**：`https://120.25.170.92:8443/`（Vue 单页应用）。用户明确弃用 VPN（WireGuard/socat/wg0/51820/wireguard-data/宿主 sysctl 已全拆）与 nginx 网关（一次性容器 + 限流都没必要），**不要再加回来**。
- 应用直接用 PEM 证书起 HTTPS，无需 keystore：compose 里 `SERVER_ADDRESS=0.0.0.0`、`SERVER_PORT=8443`、`SERVER_SSL_ENABLED=true`、`SERVER_SSL_CERTIFICATE=/app/certs/server.crt`、`SERVER_SSL_CERTIFICATE_PRIVATE_KEY=/app/certs/server.key`，并把宿主机 `docker/tls/` 挂到 `/app/certs`（证书服务器侧生成、不入库；`server.key` 600、`server.crt` 644）。
- 安全组只需放行 **TCP 8443**（与之前一致，URL 不变）；手机首次访问自签证书仍会提示“不安全”。
- **鉴权分层**：口令经请求头 `X-Agent-Admin-Key` 由 `AdminAccessFilter` 校验（`ADMIN_REQUIRE_KEY=true` 时**这是唯一凭据**，因此不再要求来源 IP 在白名单内），账号由 `AdminSessionController` 经 `POST /api/admin/session` 校验；前端把凭据存 `sessionStorage`（勾「记住账号密码」则存 `localStorage`）。
- **爆破防护**：`AdminAccessFilter` 连续 5 次口令错误即按**真实来源 IP** 封禁 10 分钟（见第 5 节第 10、14 条）。原来 nginx 的 `limit_req` 已随网关一起移除；QQ 机器人本身不受面板限流影响。

### 运维面板前端（Vue 3 前后端分离，2026-09-12 重构）

- **架构**：前端是独立工程 `web/`（Vue 3.5 + Vite 8 + vue-router 5，无 UI 框架），只通过 JSON 接口与后端通信；后端只提供 `/api/admin/*` 与静态入口。构建产物输出到 `src/main/resources/static/`，由 Dockerfile 的 node 阶段在打包镜像时生成，**部署就是 agent 这一个容器**（没有额外网关）。
- **本地开发**：`cd web && npm install`；`npm run dev`（Vite 5173，已把 `/api` 代理到 `http://127.0.0.1:8080`）。改完样式或组件必须 `npm run build`（直接写进后端 static 目录）才会进 jar。
- **目录结构**：`web/src/views/`（LoginView、DashboardView）、`web/src/panels/`（Overview / Qq / Tasks / Users / Logs 五个页签）、`web/src/components/`（StatCard、StatusPill、InfoGrid、DataTable、JsonBlock、ChartBars）、`web/src/{api,auth,labels,router}.js`，以及**集中承载全部视觉规范的 `web/src/style.css`**。
- **路由**：`createWebHashHistory`（`/#/login`、`/#/dashboard`），因此网关只需放行固定路径、不需要服务端 rewrite。
- **视觉（2026-09-12 按用户要求改成白色主调）**：白到浅蓝的极淡渐变底 + 极淡冷色网格（`body::before`：120px，竖线略清晰、横线更淡、交点小圆点，mask 向外淡出）；面板是**白色半透明玻璃**（`rgba(255,255,255,.58~.84)` 渐变 + `backdrop-filter: blur(20px) saturate(150%)` + 22px 圆角 + 白色描边 + 极淡外圈 `--ring`）；**强调色只用「淡蓝 → 白」渐变**（`#cfe0ff → #fff`，用在主按钮、选中页签、用户气泡、图表柱），蓝色不铺面积；状态色为柔和的绿/琥珀/红。改视觉只动 `web/src/style.css`。
- **文案**：界面不出现英文状态词，接口状态一律翻中文（正常/降级/异常/未启用/运行中/失败/结果未知/已回复/待机/信息/警告/错误）；原始 JSON 视图保留英文键名（那是接口数据）。`labels.js` 是唯一的状态词典，新增状态值改那里。
- **登录**：账号默认 `rootlcw` + 密码 → `POST /api/admin/session`；勾「记住账号密码」时凭据写 `localStorage`（不勾只写 `sessionStorage`，关标签页即退出），退出登录会清凭据但保留账号名。路由守卫拦截 `/#/dashboard`，接口 401 自动清登录态并回登录页。
- **用户与记忆页**：用户列表按最近活动倒序（**每个用户一条**，空时间的排最后），点「查看记录」进入聊天式视图——用户/机器人左右气泡、可上下滚动、`加载更早的消息` 分页往前翻、可选显示工具调用（`system` 消息）；同一页内还可用分段控件切到「长期记忆」「提醒任务」。
- **告警**：QQ 通道页有「发送测试告警」按钮，调 `POST /api/admin/actions/alerts/test`；另有 `POST /api/admin/actions/alerts/notify`（body `{"message":"…"}`）供 CI 等自动化推送自定义告警，同样只发给配置里的那一个人。
- **模型与搜索**（2026-09-12 新增页签）：展示 LLM 一次性调用（记忆提取/提醒解析）、对话流式调用、SearX-NG 搜索的**次数/失败/成功率/平均耗时/最近错误**，数据来自 `GET /api/admin/metrics/runtime`，由 `metrics` 包里的 `RuntimeMetrics` 在 `OpenAiCompatChatModel`、`OpenAiCompatStreamingChatModel`、`SearxngClient` 三处打点累计（进程内计数，重启归零）。出问题时先看这个页签，能立刻区分"模型慢/模型报错/搜索挂了"。
- **手机适配**：`≤720px` 概览卡 2 列、表格**转卡片列表**（靠每格 `data-label` 显示列名、`thead` 隐藏）、工具栏换行、无横向滚动。
- **安全约定**：接口文本一律用 Vue 插值（自动转义），**不要用 `v-html`**（日志、用户记忆都是用户数据）。
- **验证方式**（可复用）：`tools\ui-verify\verify_spa.py` 用 Playwright 打**公网真实地址**跑完登录/各页签/聊天视图/手机端与视觉断言（详细跑法见该目录 README）：
  ```powershell
  $env:WG_PW='<口令>'; $env:ADMIN_USERNAME='rootlcw'; $env:SPA_BASE='https://120.25.170.92:8443'; $env:SPA_TAG='v7'
  & "D:\soft\JetBrains\Python\python\python.exe" "C:\Users\33721\Desktop\wechat-agent\tools\ui-verify\verify_spa.py"
  ```
  （本机调试用同目录 `spa_server.py` 起代理、不设 `SPA_BASE`。）Playwright 需要创建命名管道，受限沙箱下会 `PermissionError: [WinError 5]`。

### 运维告警推送（2026-09-12 新增）

- `alert` 包里的 `AlertNotifier` 每 60 秒检查一次：QQ 网关是否断开、MySQL/Redis/Quartz 是否可用、磁盘可用空间是否低于阈值（默认 2GB）、堆内存是否超过阈值（默认 85%）。
- 只在**问题新出现**或**问题恢复**时推送，同一问题在 `repeat-minutes`（默认 30 分钟）内不重复；启动后有 2 分钟宽限期，避免重启瞬间网关未连上就误报。
- 推送目标由 `.env` 的 `ALERT_QQ_OPENID` 指定（**是 openid，不是 QQ 号**；管理员本人的 openid 是 `9C81741E2EFD75552F7FB3EB4B0D821C`，从 `user_profile` 里按最近活动确认），**只推给这一个人**，不会推给其它用户。
- 手动验证：面板「QQ 通道」页的「发送测试告警」按钮，或 `POST /api/admin/actions/alerts/test`（需带口令头）。
- 注意 QQ 官方机器人对**主动消息**有额度限制，因此告警是「尽力而为」：发送失败会记 WARN 日志（`运维告警推送失败（可能是 QQ 主动消息额度限制）`），面板里的状态永远是最可靠的来源。
- 配置项：`ALERT_ENABLED`、`ALERT_QQ_OPENID`、`ALERT_REPEAT_MINUTES`、`ALERT_CHECK_INTERVAL_MS`、`ALERT_DISK_FREE_MIN_BYTES`、`ALERT_HEAP_USED_MAX_PERCENT`、`ALERT_STARTUP_GRACE_SECONDS`。

## 4. CI/CD

- 文件：`.github/workflows/deploy-remote.yml`，触发条件 `push: main` 或手动 `workflow_dispatch`。
- 构建步骤用 `docker/build-push-action@v6` + `cache-from/to: type=gha` 复用上一次的层，Dockerfile 里 npm/Maven 也用了 BuildKit cache mount（实测纯后端改动约 192 秒、含前端全量约 240 秒）。
- 流程：runner 上 `docker build` → `docker save | gzip` → scp 镜像与 compose/settings 到服务器 → `docker load` → `docker compose up -d --no-build agent` → `docker image prune -f`。MySQL/Redis/SearXNG 及其卷不受影响。
- 已配置的 GitHub Secrets：`DEPLOY_HOST`、`DEPLOY_USER`、`DEPLOY_SSH_KEY`（专用 ed25519 部署私钥；对应公钥已写入服务器 `~/.ssh/authorized_keys`，本地私钥文件已删除，需要轮换时重新生成并更新 Secret）、`ADMIN_API_KEY`（面板口令，供部署后自检使用）。
- **部署后自检**（2026-09-12 新增）：部署完等应用就绪，然后检查「首页 200 / 前端 JS 资源 200 / 无口令 401 / 带口令 200 / 账号密码登录 200」；任一项不符即调用告警接口推一条 QQ 消息并把流水线置红。也就是说**改坏了会被系统自己发现并通知你**，不用等你打开面板才发现。
- **文档改动不触发构建**：`paths-ignore` 覆盖 `**.md`、`docs/**`、`AGENTS.md`、`LICENSE`（实测：纯文档 push 后流水线条数不增加）。
- 查看流水线：`gh run list --repo liche719/wechat-qq-agent` / `gh run watch <id> --repo liche719/wechat-qq-agent --exit-status`。

## 5. 已知坑与约定（都踩过）

1. MySQL 必须钉 `8.0.46`：数据卷由 8.0.46 创建，换 8.0.27 会导致 InnoDB 启动失败。
2. Quartz：`job-store-type: jdbc`，`initialize-schema` 必须为 `never`（历史上是 `always`，有重建表风险，已修）。
3. SearXNG 的 Google / DuckDuckGo / Brave / Startpage 在服务器上网络不可达，属线路限制而非配置错误；实际可用 bing / baidu / sogou / 360。
4. 容器内绑定 `127.0.0.1` 会让 docker 端口映射失效，所以 agent 用 host 网络；同时管理后台的来源 IP 校验 `ADMIN_ALLOWED_IPS`（默认 `127.0.0.1,::1`）是**精确匹配、不支持 CIDR**，host 网络下才自然放行。
5. 不要把密码放进命令行参数：会被本机安全策略拦截，也应避免；改用交互式 SSH 或 stdin 传参。
6. 用管道把 `.env` 写到服务器会带 UTF-8 BOM，docker compose 读取前需去掉（`sed -i '1s/^\xEF\xBB\xBF//'`）。
7. 提交信息风格：小写英文短句（例：`run remote agent on host network and document deploy secrets`）。
8. 不要 `git commit`/建分支除非用户明确要求；本项目历史提交由用户账号 `YOLO <3372134858@qq.com>` 完成。
9. **`/api/admin/*` 的鉴权语义**（2026-09-12 修正）：`AdminAccessFilter` 原先对 dashboard 路径**只校验来源 IP 就直接放行**，密钥形同虚设（面板数据全靠 nginx Basic Auth 挡着）。现已统一为「带正确 `X-Agent-Admin-Key` 头，或在 require-key=false 时回环免密钥」；`require-key=true`（服务器 `.env`）时面板接口必须带口令。历史测试 `AdminAccessFilterTest` 正是按这个语义写的（其中「回环在 local 模式下免密钥」一条与项目文档相冲突，属预期差异）；CI 的 Dockerfile 用 `-DskipTests`，不跑测试。
10. 失败封禁按**来源 IP** 计数：直连来源是回环时用 `X-Forwarded-For` 的**最后一段**（代理追加的那段才是真实地址；取第一段会被客户端伪造，既可能绕过封禁也可能反过来封禁别人）。实测：服务器本机连错 5 次后本机被 429，同时另一来源 IP 仍然 200 —— 别人乱输不会连累你。
11. **CSS 里只写标准 `backdrop-filter`**：手写一行 `-webkit-backdrop-filter` 会被 Vite 8 的 CSS 压缩（lightningcss）合并掉标准属性，构建产物里只剩带前缀的那条，而 Chromium 根本不认（`CSS.supports('-webkit-backdrop-filter')` 为 false）→ 毛玻璃**静默失效**。让构建工具自己加前缀即可。
12. 不要用 PowerShell 5.1 的 `Get-Content -Raw` + `Set-Content` 往返改 UTF-8 源文件：会按 ANSI 读取、再写成带 BOM 的 UTF-8，中文全变乱码（Python 直接语法报错）。用 write 工具或 `[IO.File]::ReadAllText` + `WriteAllText(..., UTF8Encoding($false))`。
13. 前端改动必须 `cd web && npm run build`（或走 CI 的 Dockerfile）才会进 jar；`src/main/resources/static/` 已在 `.gitignore`（构建产物不入库），新克隆的仓库直接 `mvn package` 是**不带界面**的。
14. **经 stdin 传给 `bash` 的远程脚本里不能直接用 `docker exec -i`**：它会读走 stdin（也就是脚本剩下的部分），导致脚本在后面某行静默中断。要么 `< /dev/null`，要么把整段 SQL 用 heredoc（heredoc 会把该命令的 stdin 换成 here-doc，反而正常）。
15. 后端 `AdminDashboardController.userList()` 用 `String.valueOf(u.getLastSeenAt())`，空值会序列化成**字符串 `"null"`**，前端按字符串排序时 `"null"` 会排到最前（`'n' > '2'`）。前端 `labels.js` 已把 `"null"/"undefined"/"NaN"` 当空值处理，用户列表也只用合法日期参与排序。
16. 用户记忆/微信数据：`user_profile.last_channel IS NULL` 的历史账号都是微信时代的测试账号（`wx_*` 与 `*@im.wechat`），用户已于 2026-09-12 要求清空，**已删除并留全库备份** `/root/wechat-agent-backup-20260912015146.sql.gz`（服务器上，98KB，已 chmod 600）。删除时用的条件：`last_channel IS NULL AND (user_id LIKE 'wx\_%' OR user_id LIKE '%@im.wechat')`；模拟器测试账号 `sim-user-qq` 同日一并删除。
17. **服务器旧镜像会累积**（2026-09-12 已根治）：Docker 镜像不可变，CI 每次部署 load 一个新 tag 的镜像，旧的**不会自动消失**；原来的 `docker image prune -f` 只删悬空镜像（无 tag 的中间层），带 tag 的 `wechat-agent:<sha>` 永远不算悬空，于是攒了 11 个 × 431MB。现在部署步骤里加了一句「按创建时间只保留最新两个 tag」，`workflow_dispatch` 手动跑同样生效。回滚方式：`AGENT_IMAGE=wechat-agent:<上一个sha> docker compose -f docker-compose.remote.yml up -d --no-build agent`。
18. **数据卷 ≠ 备份**：`wechat-agent-infra_mysql-data` 和数据库在同一台机器、同一块云盘上（`/var/lib/docker/volumes/`）。卷只能扛「容器重装」，扛不住误删（例如我们删 17 个微信用户那种操作）、扛不住误迁移、也扛不住机器/云盘故障。`mysqldump` 出来的 dump 才是备份，**不要因为「有卷」就删掉备份**；更强的做法是定期导出后加密传到异地。
19. 服务器上的旧 `.env.bak-*` 会带着历史口令，只留最近 2 个即可。
20. **容器 json-file 日志默认不轮转**：docker 的 json-file 驱动如果没有 `max-size`，容器 stdout 会无限增长（Spring Boot 的 root appender 同时挂控制台，所以每条日志都会落一份）。4 个服务已统一配 `logging.options: {max-size: 10m, max-file: 3}`（每个容器最多 30MB）。应用自身的文件日志由 logback 按 30 天轮转，但它写在**容器内**（没有挂卷），容器重建就没了 —— 面板「日志」页读的就是它。

## 6. Windows / PowerShell 环境注意

- PowerShell 不支持 heredoc（`<<'EOF'`），用 `@'...'@` here-string。
- `Remove-Item` 常被安全策略拒绝；删除文件用 `cmd /c del /f "绝对路径"`。
- `apply_patch` 的 `.bat` 包装器会丢换行，多行补丁不可靠：可改为用 `[IO.File]::WriteAllText` + `String.Replace` 直接改写，或直接调用 `codex.exe --codex-run-as-apply-patch $patch`（路径见 `Get-Command apply_patch` 指向的 .bat）。
- 写文件统一用 LF 换行，避免 git 警告与补丁解析失败。
- 命令默认工作目录是 workspace 根 `C:\Users\33721\Desktop\wechat-agent`，而 git 仓库在子目录 `wechat-agent-java`，注意路径。
- **本机 HTTPS 被 SteamTools 中间拦截**（系统根证书里装了 `SteamTools Certificate / BeyondDimension`，系统代理 `127.0.0.1:3067`）。后果与绕法：
  - `schannel` 后端在本 harness 里会报 `SEC_E_NO_CREDENTIALS (0x8009030e)`（curl.exe 与 git 都一样）；`OpenSSL` 后端又不认 SteamTools 根证书（`unable to get local issuer certificate`）。
  - 已在**仓库本地**（`.git/config`，未入库、未改全局）设置：`http.sslBackend=openssl` + `http.sslCAInfo=C:/Users/33721/Desktop/wechat-agent/.git-ca/windows-roots.pem`（该文件由 Windows 证书库导出，150 张根证书）。删掉它会再次无法 push。
  - `git push` 还需凭据管理器，而沙箱若禁止创建命名管道会报 `couldn't create signal pipe, Win32 error 5`；放宽文件策略后即可通过。SSH 方式走不通（本机两个密钥都没注册到 GitHub，且 22 端口被墙，443 端口同样 `Permission denied (publickey)`）。
- **Playwright 可用但需管道权限**：`D:\soft\JetBrains\Python\python\python.exe` 已装 playwright + Chromium，但启动浏览器要创建命名管道，受限沙箱下会 `PermissionError: [WinError 5]`；Node 在 `D:\soft\Node.js\node.exe`（可用 `node --check` 校验前端 JS 语法）。

## 7. 当前状态（2026-09-12 03:20）

- 远程 `wechat-agent-java` 运行中（commit `bc5d004`），**应用自带 HTTPS 监听 `0.0.0.0:8443`**；`status=UP`、QQ 通道 `UP`。
- 面板入口：`https://120.25.170.92:8443/`（Vue 单页应用，6 个页签：总览 / QQ 通道 / 模型与搜索 / 任务 / 用户与记忆 / 日志）→ 未登录进 `/#/login`；账号 `rootlcw` + 密码（明文只在用户手上）。勾「记住账号密码」后凭据存浏览器本地。
- 远程**只有 4 个容器**（nginx 网关与 VPN 全部拆除），全部配了 10m×3 的日志上限；只有 mysql/redis/searxng 三个数据卷（**严禁删除**）。
- 公网暴露面：**22（SSH）、8443（面板）**；8080 / 51820 / 51821 均未开。内存 used 约 940MB / available 930MB。
- 数据：`user_profile` **3**（全是本人的 QQ 号）、`conversation_memory` 332（本人 304）、`reminder_task` **14**、`user_work_memory` 49、`user_core_memory` 17、`operation_log` 67。微信与模拟器残留已清空。
- CI 现在是自验证的：部署后自动检查页面/鉴权/登录接口，失败会推 QQ 并置红；旧镜像只保留两个；纯文档改动不触发构建。
- 告警已上线（`ALERT_ENABLED=true` → 本人的 openid），已实测推送成功（测试告警 + 自定义 notify 各一次）。
- 遗留可选项：换成受信任证书（**需要域名**，8443 不需备案）；`/api/clawbot/*` 代码保留但已无页面入口；`.trash\research-20260912.zip` 待用户确认后删除。
- 本地：Docker Desktop 未启动，本地 JAR 未运行。

## 8. 凭据索引（只写位置，不写明文）

| 用途 | 位置 |
|---|---|
| 本地 LLM / QQ 凭据 | `wechat-agent-java\.env`（gitignore） |
| 服务器容器凭据 | 服务器 `120.25.170.92:/opt/wechat-agent-infra/.env`（600） |
| 运维面板登录 | 服务器 `.env` 的 `ADMIN_USERNAME`（现为 `rootlcw`）与 `ADMIN_API_KEY`（600）；明文只由用户保存 |
| 服务器 SSH root 密码 | 由用户提供 |
| 部署私钥 | 仅存于 GitHub Secrets `DEPLOY_SSH_KEY` |

## 9. 历史会话

Codex 会话原始记录在 `C:\Users\33721\.codex\sessions\`（Codex 专有格式，其他 harness 读不到），因此本文件是唯一可迁移的记忆载体；如需更多细节可回头检索这些 jsonl。

## 10. 工作区结构（2026-09-12 整理过，约 161MB）

```
C:\Users\33721\Desktop\wechat-agent\
├─ AGENTS.md                    工作区记忆入口
├─ DS-HARNESS-PROMPT.md         用户给 AI 的初始提示词
├─ tools\ui-verify\             面板端到端验证工具（脚本 + README + 最新一轮截图）
├─ .git-ca\                     导出的系统根证书，**git push 依赖它，不能删**
├─ .trash\                      整理时留下的 research 打包（确认不用可删）
└─ wechat-agent-java\           git 仓库（源码、配置、AGENTS.md 完整记忆）
   ├─ target\ (~95MB)           Maven 构建产物，`mvn package` 可重建
   ├─ web\node_modules\ (~53MB) 前端依赖，`npm install` 可重建
   └─ logs\ (~5.5MB)            本地跑 JAR 时的日志
```

- 已删除（2026-09-12）：`research/`（打包进 `.trash`）、`wechat-agent-java/{tmp,backup,stored-media}`、工作区根 `logs/`、空的 `docker/`、`.ui-test/`（并入 `tools/ui-verify`）。
- `backup/`、`stored-media/`、`logs/`、`tmp/` 都是**本地跑 JAR 时生成**的，远程服务器各有独立一份；以后本地调试完顺手删。
- 验证面板不再用 `.ui-test`，跑法见 `tools\ui-verify\README.md`。
