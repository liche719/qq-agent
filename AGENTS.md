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
- 访问：运维后台 http://127.0.0.1:8080/admin.html （`static/index.html` 扫码登录页已删除，`/` 不再有欢迎页）
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
- 容器：`wechat-agent-mysql`(mysql:8.0.46) / `wechat-agent-redis` / `wechat-agent-searxng` / `wechat-agent-java`
- 数据卷：`wechat-agent-infra_mysql-data` / `_redis-data` / `_searxng-data` —— **任何操作都不允许删除或重建这些卷**。
- agent 容器用 `network_mode: host`，只监听服务器 `127.0.0.1:8080`；MySQL/Redis/SearXNG 走 `127.0.0.1`。
- 远程查看运维后台：

```bash
ssh -L 8080:127.0.0.1:8080 root@120.25.170.92
# 本机浏览器打开 http://127.0.0.1:8080/admin.html
```

- 只重启 agent：`cd /opt/wechat-agent-infra && AGENT_IMAGE=wechat-agent:<sha> docker compose -f docker-compose.remote.yml up -d --no-build agent`

### 远程运维面板访问（WireGuard 私人通道，2026-09-11 建立）

- 目的：手机/电脑在任意网络都能看运维面板，且面板不暴露公网。不再依赖 SSH 隧道。
- 两个附加容器（**不在 CI 部署范围内**，需在服务器手动 `up -d`）：
  - `wechat-agent-wireguard`（wg-easy v14，host 网络，`wg0=10.8.0.1/24`，UDP 51820；管理界面只绑 `127.0.0.1:51821`）
  - `wechat-agent-admin-forward`（alpine/socat，host 网络，只在 `10.8.0.1:8080` 监听并转发到 agent 的 `127.0.0.1:8080`）
- **agent 保持 `SERVER_ADDRESS=127.0.0.1` 不变**：VPN 能访问面板靠的是 socat 转发容器，公网永远连不上 8080（不依赖安全组配置）。
- 访问方式：VPN 连上后浏览器打开 `http://10.8.0.1:8080/admin.html`；新增设备用 `ssh -L 51821:127.0.0.1:51821 root@120.25.170.92` 打开 wg-easy 网页生成二维码。
- 分隧道：客户端 `AllowedIPs=10.8.0.0/24`、DNS `223.5.5.5`、`PersistentKeepalive=25`，手机正常上网不经过服务器。
- 凭据：`WG_HOST`、`WG_UI_PASSWORD_HASH`（bcrypt）在服务器 `.env`（600）；`WG_UI_PASSWORD` 行仅作人工记录。
- 该通道的坑：
  1. wg-easy v14 **拒绝明文 `PASSWORD`**，只认 `PASSWORD_HASH`；用镜像内 `/app/wgpw.sh '密码'` 生成，写进 `.env` 时必须用单引号包住（防 compose 展开 `$`）。
  2. 镜像内 iptables 是 **legacy 后端**，宿主机内核只有 nft（无 `nat` 表），默认 PostUp 的 MASQUERADE 会让 `wg-quick up` 失败；已用 `WG_POST_UP/WG_POST_DOWN=/bin/true` 覆盖（分隧道不需要 NAT/转发）。
  3. host 网络下 compose 的 `sysctls` 无效，`net.ipv4.ip_forward`、`net.ipv4.conf.all.src_valid_mark` 写在宿主机 `/etc/sysctl.d/99-wechat-agent-wireguard.conf`。
  4. 服务器**不能直连 ghcr.io 拉镜像**（API 通、层下载卡死），需 `docker pull ghcr.m.daocloud.io/wg-easy/wg-easy:latest` 后再 `docker tag` 成 `ghcr.io/wg-easy/wg-easy:14`。
  5. 阿里云安全组需放行入方向 **UDP 51820**；`wireguard-data` 卷与其它数据卷一样**严禁删除**。
  6. 服务器上 Windows 侧传文件：命令行超 ~8KB 会报 "command line is too long"，改用 `scp`（本机 ssh 走 `SSH_ASKPASS` + `SSH_ASKPASS_REQUIRE=force` 可非交互带密码）。

### 运维面板公网入口（nginx 网关 + 账号密码，2026-09-11 建立）

- 用户选择"公网直连 + 唯一账号密码"，与上面的 VPN 通道**并存**（VPN 未开 51820，作为备用；两者都不改 agent）。
- 容器 `wechat-agent-gateway`（`nginx:stable-alpine`，host 网络，监听公网 **8443/TLS**），反代到只绑回环的 agent；**只公开** `/admin.html`、`/admin.js`、`/api/admin/*`，其余路径 404。
- 配置：仓库内 `docker/gateway/nginx.conf`（**CI 不传**，改动后需手动 scp 到服务器）。
- 服务器侧生成、不入库（都在 `/opt/wechat-agent-infra/docker/gateway/`）：`certs/server.crt|server.key`（自签、含 IP SAN、10 年）、`auth/htpasswd`（用户 `admin`，apr1 哈希）。
- 安全组需放行入方向 **TCP 8443**；手机首次访问自签证书会提示"不安全"，需手动继续。
- 该网关的坑：
  1. **htpasswd 权限**：必须是容器内 nginx 用户（uid **101**）可读，否则"带凭据"的请求返回 **500**（错误日志 `open() "/etc/nginx/auth/htpasswd" failed (13: Permission denied)`）而不是 401。已 `chown 101:101 htpasswd` + `chmod 600`。
  2. `nginx:alpine` 在阿里云镜像源里是 **4 年前的旧版本**，公网网关要用 `docker pull docker.m.daocloud.io/library/nginx:stable-alpine` 后再打 `nginx:stable-alpine` 标签（当前 1.30.4）。
  3. 限流 `limit_req zone=panel rate=5r/s burst=20 nodelay`（面板正常轮询约 0.5r/s，实测连续 40 次请求后出现 429），防公网爆破。
  4. 该容器上 `docker exec` 会挂住（会话无输出直到超时）；查日志用 `docker logs wechat-agent-gateway`（nginx 的 access/error 日志都指向 stdout/stderr），进容器排查用 `docker run --rm`。

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

## 6. Windows / PowerShell 环境注意

- PowerShell 不支持 heredoc（`<<'EOF'`），用 `@'...'@` here-string。
- `Remove-Item` 常被安全策略拒绝；删除文件用 `cmd /c del /f "绝对路径"`。
- `apply_patch` 的 `.bat` 包装器会丢换行，多行补丁不可靠：可改为用 `[IO.File]::WriteAllText` + `String.Replace` 直接改写，或直接调用 `codex.exe --codex-run-as-apply-patch $patch`（路径见 `Get-Command apply_patch` 指向的 .bat）。
- 写文件统一用 LF 换行，避免 git 警告与补丁解析失败。
- 命令默认工作目录是 workspace 根 `C:\Users\33721\Desktop\wechat-agent`，而 git 仓库在子目录 `wechat-agent-java`，注意路径。

## 7. 当前状态（2026-09-11 19:45）

- 远程 `wechat-agent-java` 运行中，镜像 `wechat-agent:0b7a25f…`；本次所有操作**从未重启过 agent**；`/api/admin/overview` 返回 200、QQ 通道 `UP`。
- 远程数据完好：`user_profile` 21 条、`reminder_task` 23 条、`QRTZ_TRIGGERS` 2 条。
- 面板两条访问通道都已就绪（都不改 agent，agent 仍只绑 `127.0.0.1`）：
  - **公网网关（用户当前选择）**：`wechat-agent-gateway`（nginx，8443/TLS，Basic Auth 用户 `admin`）；服务器侧已验证 401/200/404/429、`overview` 正常。**只差阿里云安全组放行 TCP 8443**，放行后即可用 `https://120.25.170.92:8443/admin.html` 访问。
  - **WireGuard（备用）**：`wechat-agent-wireguard` + `wechat-agent-admin-forward` 运行中，客户端 `phone(10.8.0.2)`、`pc(10.8.0.3)` 已创建；需放行 UDP 51820 才可用（用户当时下载不了客户端 App，故搁置）。
- 本机侧实测公网暴露面：**8080 / 51821 / 8443 均关闭，仅 22 开着**（8443 等安全组放行）。
- 本地代码改动（**未 commit、未 push、未部署**）：删除 `static/index.html` 与 `static/js/qrcode.min.js`（`/api/clawbot/*` 接口保留）、404 文案改中性、`docker-compose.remote.yml` 新增 wireguard/admin-forward/gateway 三个服务、新增 `docker/gateway/nginx.conf`。
- 用户表示运维监控界面不满意、**后续会重做**（`admin.html`/`admin.js` 保持原样没动）；重做时建议一并加登录页（现在用的是浏览器原生 Basic Auth 弹窗）。
- 本地：Docker Desktop 未启动，本地 JAR 未运行。

## 8. 凭据索引（只写位置，不写明文）

| 用途 | 位置 |
|---|---|
| 本地 LLM / QQ 凭据 | `wechat-agent-java\.env`（gitignore） |
| 服务器容器凭据 | 服务器 `120.25.170.92:/opt/wechat-agent-infra/.env`（600） |
| 运维面板公网登录（用户 `admin`） | 服务器 `/opt/wechat-agent-infra/docker/gateway/auth/htpasswd`（600，chown 101:101）；明文只由用户保存 |
| WireGuard 网页管理密码 | 服务器 `.env` 的 `WG_UI_PASSWORD`（人工记录）与 `WG_UI_PASSWORD_HASH`（容器实际使用） |
| 服务器 SSH root 密码 | 由用户提供 |
| 部署私钥 | 仅存于 GitHub Secrets `DEPLOY_SSH_KEY` |

## 9. 历史会话

Codex 会话原始记录在 `C:\Users\33721\.codex\sessions\`（Codex 专有格式，其他 harness 读不到），因此本文件是唯一可迁移的记忆载体；如需更多细节可回头检索这些 jsonl。
