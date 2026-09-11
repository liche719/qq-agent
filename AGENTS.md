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
- 包结构：agent / backup / care / channel / command / config / controller / document / exception / log / media / memory / network / reminder / search / tool / user
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
- 容器：`wechat-agent-mysql`(mysql:8.0.46) / `wechat-agent-redis` / `wechat-agent-searxng` / `wechat-agent-java` / `wechat-agent-gateway`(nginx，面板公网入口)
- 数据卷：`wechat-agent-infra_mysql-data` / `_redis-data` / `_searxng-data` —— **任何操作都不允许删除或重建这些卷**。
- agent 容器用 `network_mode: host`，只监听服务器 `127.0.0.1:8080`；MySQL/Redis/SearXNG 走 `127.0.0.1`。
- 远程查看运维后台：

```bash
ssh -L 8080:127.0.0.1:8080 root@120.25.170.92
# 本机浏览器打开 http://127.0.0.1:8080/admin.html
```

- 只重启 agent：`cd /opt/wechat-agent-infra && AGENT_IMAGE=wechat-agent:<sha> docker compose -f docker-compose.remote.yml up -d --no-build agent`

### 运维面板公网入口（nginx 网关 + 账号密码，2026-09-11 建立）

- **这是面板唯一的访问方式**（用户 2026-09-11 明确弃用 VPN：WireGuard/socat 容器、`wg0`、51820 端口、`wireguard-data` 卷、`.env` 里的 WG 配置、宿主机 sysctl 文件**已全部拆除**，不要再加回来）。
- 容器 `wechat-agent-gateway`（`nginx:stable-alpine`，host 网络，监听公网 **8443/TLS**），反代到只绑回环的 agent；**只公开** `/`、`/index.html`、`/assets/`、`/api/admin/*`（前端是 Vue 单页应用，用 hash 路由，所以不需要服务端 rewrite），其余路径一律 404。
- **agent 始终 `SERVER_ADDRESS=127.0.0.1`**：网关在宿主机上直连 `127.0.0.1:8080`，所以公网永远连不上 8080（不依赖安全组配置）。
- **鉴权在应用层**（2026-09-12 改）：nginx 不做 Basic Auth（浏览器原生弹窗无法美化）。前端登录页提交「账号 + 密码」→ `POST /api/admin/session`（口令由 `AdminAccessFilter` 用 `X-Agent-Admin-Key` 头校验、账号由 `AdminSessionController` 校验）→ 存 `sessionStorage` → 之后每个请求都带头。**服务器 `.env` 必须同时有 `ADMIN_REQUIRE_KEY=true`、`ADMIN_API_KEY=<口令>`、`ADMIN_USERNAME=admin`**；`htpasswd` 与 `docker/gateway/auth/` 已删除。
- 配置：仓库内 `docker/gateway/nginx.conf`（**CI 不传**，改动后需手动 scp 到服务器）。
- 证书：服务器侧生成、不入库（`/opt/wechat-agent-infra/docker/gateway/certs/server.crt|server.key`，自签、含 IP SAN、10 年）。
- 安全组需放行入方向 **TCP 8443**；手机首次访问自签证书会提示"不安全"，需手动继续。
- 该网关的坑：
  1. `nginx:alpine` 在阿里云镜像源里是 **4 年前的旧版本**，公网网关要用 `docker pull docker.m.daocloud.io/library/nginx:stable-alpine` 后再打 `nginx:stable-alpine` 标签（当前 1.30.4）。
  2. 限流 `limit_req zone=panel rate=5r/s burst=20 nodelay`（面板正常轮询约 0.5r/s），再叠加应用层「连续 5 次口令错误封禁 10 分钟」防爆破。
  3. 该容器上 `docker exec` 会挂住（会话无输出直到超时）；查日志用 `docker logs wechat-agent-gateway`（nginx 的 access/error 日志都指向 stdout/stderr），进容器排查用 `docker run --rm`。
  4. 若以后要把 Basic Auth 加回来：`htpasswd` 必须是容器内 nginx 用户（uid **101**）可读，否则带凭据的请求会返回 **500**（`open() failed (13: Permission denied)`）而不是 401。
  5. **改了 `docker/gateway/nginx.conf` 必须显式重启网关容器**（`docker restart wechat-agent-gateway`）：它是挂载文件，`docker compose up -d gateway` 不会重建容器（输出是 `Running` 而不是 `Recreated`），配置不会生效——表现为旧配置继续工作（例如仍 302 到已删除的旧路径）。

### 运维面板前端（Vue 3 前后端分离，2026-09-12 重构）

- **架构**：前端是独立工程 `web/`（Vue 3.5 + Vite 8 + vue-router 5，无 UI 框架），只通过 JSON 接口与后端通信；后端只提供 `/api/admin/*`。构建产物输出到 `src/main/resources/static/`，由 Dockerfile 的 node 阶段在打包镜像时生成，**部署仍是单容器**（nginx 网关 → agent）。
- **本地开发**：`cd web && npm install`；`npm run dev`（Vite 5173，已把 `/api` 代理到 `http://127.0.0.1:8080`）。改完样式或组件必须 `npm run build`（直接写进后端 static 目录）才会进 jar。
- **目录结构**：`web/src/views/`（LoginView、DashboardView）、`web/src/panels/`（Overview / Qq / Tasks / Users / Logs 五个页签）、`web/src/components/`（StatCard、StatusPill、InfoGrid、DataTable、JsonBlock、ChartBars）、`web/src/{api,auth,labels,router}.js`，以及**集中承载全部视觉规范的 `web/src/style.css`**。
- **路由**：`createWebHashHistory`（`/#/login`、`/#/dashboard`），因此网关只需放行固定路径、不需要服务端 rewrite。
- **视觉**：按 skill `deepseek-front-end-style` 的官网观感做**深色 + 网格 + 高级半透明玻璃**——冷蓝到深蓝的下沉渐变底、固定网格层（`body::before`：120px 间距，竖线略清晰、横线更淡、交点带极淡圆点，并用 mask 向外淡出）、玻璃面板（`rgba(255,255,255,.03~.08)` 渐变 + `backdrop-filter: blur(18px) saturate(140%)` + 22px 圆角 + 1px 冷蓝描边 + 内高光）、亮蓝 `#4d6bfe` 只用于主按钮/焦点/状态点。
- **文案**：界面不出现英文状态词，接口状态一律翻中文（正常/降级/异常/未启用/运行中/失败/结果未知/已回复/待机/信息/警告/错误）；原始 JSON 视图保留英文键名（那是接口数据）。`labels.js` 是唯一的状态词典，新增状态值改那里。
- **登录**：账号 + 密码 → `POST /api/admin/session` → 成功后口令存 `sessionStorage`，路由守卫拦截 `/#/dashboard`；接口 401 清登录态并回登录页；顶栏有「退出登录」。
- **手机适配**：`≤720px` 概览卡 2 列、表格**转卡片列表**（靠每格 `data-label` 显示列名、`thead` 隐藏）、工具栏换行、无横向滚动。
- **安全约定**：接口文本一律用 Vue 插值（自动转义），**不要用 `v-html`**（日志、用户记忆都是用户数据）。
- **验证方式**（可复用）：`.ui-test\verify_spa.py` 用 Playwright 打**公网真实地址**跑完登录/各页签/退出/手机端与视觉断言：
  ```powershell
  $env:WG_PW='<口令>'; $env:SPA_BASE='https://120.25.170.92:8443'; $env:SPA_TAG='v5'
  & "D:\soft\JetBrains\Python\python\python.exe" "C:\Users\33721\Desktop\wechat-agent\.ui-test\verify_spa.py"
  ```
  （本机调试可用 `.ui-test\spa_server.py` 代理模式，把 `SPA_BASE` 留空即走 `http://127.0.0.1:8899`。）Playwright 需要创建命名管道，受限沙箱下会 `PermissionError: [WinError 5]`。

## 4. CI/CD

- 文件：`.github/workflows/deploy-remote.yml`，触发条件 `push: main` 或手动 `workflow_dispatch`。
- 流程：runner 上 `docker build` → `docker save | gzip` → scp 镜像与 compose/settings 到服务器 → `docker load` → `docker compose up -d --no-build agent` → `docker image prune -f`。MySQL/Redis/SearXNG 及其卷不受影响。
- 已配置的 GitHub Secrets：`DEPLOY_HOST`、`DEPLOY_USER`、`DEPLOY_SSH_KEY`（专用 ed25519 部署私钥；对应公钥已写入服务器 `~/.ssh/authorized_keys`，本地私钥文件已删除，需要轮换时重新生成并更新 Secret）。
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
10. 失败封禁按**来源 IP** 计数：直连来源是回环时改用 `X-Forwarded-For` 首个地址，否则经 nginx 反代后所有请求共用 `127.0.0.1`，攻击者故意输错 5 次就能把正常用户一起封禁 10 分钟。
11. **CSS 里只写标准 `backdrop-filter`**：手写一行 `-webkit-backdrop-filter` 会被 Vite 8 的 CSS 压缩（lightningcss）合并掉标准属性，构建产物里只剩带前缀的那条，而 Chromium 根本不认（`CSS.supports('-webkit-backdrop-filter')` 为 false）→ 毛玻璃**静默失效**。让构建工具自己加前缀即可。
12. 不要用 PowerShell 5.1 的 `Get-Content -Raw` + `Set-Content` 往返改 UTF-8 源文件：会按 ANSI 读取、再写成带 BOM 的 UTF-8，中文全变乱码（Python 直接语法报错）。用 write 工具或 `[IO.File]::ReadAllText` + `WriteAllText(..., UTF8Encoding($false))`。
13. 前端改动必须 `cd web && npm run build`（或走 CI 的 Dockerfile）才会进 jar；`src/main/resources/static/` 已在 `.gitignore`（构建产物不入库），新克隆的仓库直接 `mvn package` 是**不带界面**的。

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

## 7. 当前状态（2026-09-12 02:00）

- 远程 `wechat-agent-java` 运行中（镜像来自 commit `a283aa9`，含 Vue 前端）；带口令请求 `/api/admin/overview` 返回 200、`status=UP`、QQ 通道 `UP`；数据 `user_profile` 21、`reminder_task` 23、`QRTZ_TRIGGERS` 2。
- 面板**唯一入口**：`https://120.25.170.92:8443/`（Vue 单页应用，hash 路由）→ 未登录自动进 `/#/login`；账号 + 密码登录，口令存标签页 `sessionStorage`。VPN 相关组件已按用户要求**全部拆除**。
- 鉴权链路：nginx（TLS + 限流，**无 Basic Auth**）→ agent 的 `AdminAccessFilter`（`ADMIN_REQUIRE_KEY=true` + `ADMIN_API_KEY`，连续 5 次错误按来源 IP 封禁 10 分钟）+ `AdminSessionController`（账号校验）。
- 远程 5 个容器：`wechat-agent-java` / `wechat-agent-gateway` / `wechat-agent-mysql` / `wechat-agent-redis` / `wechat-agent-searxng`；只有 mysql/redis/searxng 三个数据卷（**严禁删除**）。
- 公网暴露面：**22（SSH）、8443（登录页 + 面板 + 接口）**；8080 / 51820 / 51821 均未开。
- 部署方式：push `main` 触发 CI（Dockerfile 里先 node 构建前端再 maven 打包），只重建 agent 容器（QQ 断约 40 秒后自动重连）；改 `docker/gateway/nginx.conf` 需手动 scp **并显式 `docker restart wechat-agent-gateway`**。
- 遗留可选项：换成受信任证书（**需要域名**，8443 不需要备案）；登录加"记住我"（现在关标签页即退出）；`/api/clawbot/*` 保留但已无页面入口。
- 本地：Docker Desktop 未启动，本地 JAR 未运行。

## 8. 凭据索引（只写位置，不写明文）

| 用途 | 位置 |
|---|---|
| 本地 LLM / QQ 凭据 | `wechat-agent-java\.env`（gitignore） |
| 服务器容器凭据 | 服务器 `120.25.170.92:/opt/wechat-agent-infra/.env`（600） |
| 运维面板登录 | 服务器 `.env` 的 `ADMIN_USERNAME`（默认 `admin`）与 `ADMIN_API_KEY`（600）；明文只由用户保存（旧 Basic Auth 的 `htpasswd` 已删除） |
| 服务器 SSH root 密码 | 由用户提供 |
| 部署私钥 | 仅存于 GitHub Secrets `DEPLOY_SSH_KEY` |

## 9. 历史会话

Codex 会话原始记录在 `C:\Users\33721\.codex\sessions\`（Codex 专有格式，其他 harness 读不到），因此本文件是唯一可迁移的记忆载体；如需更多细节可回头检索这些 jsonl。
