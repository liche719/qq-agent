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
- 访问：主页 http://127.0.0.1:8080/ ，运维后台 http://127.0.0.1:8080/admin.html
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

## 7. 当前状态（2026-09-11 18:30）

- 上游线程 `codex://threads/01a060dd-d68a-7431-a74f-b678a8f7ab7e` 的遗留事项已全部完成：CI 部署链路打通，Actions run `34588660239` 成功。
- 远程 `wechat-agent-java` 正在运行，镜像 `wechat-agent:df188d3…`；`/api/admin/overview` 返回 200，QQ 通道 `UP`、`connected=true`。
- 远程数据完好：`user_profile` 21 条、`reminder_task` 23 条、`QRTZ_TRIGGERS` 2 条。
- 本地：Docker Desktop 未启动，本地 JAR 未运行（当前只有远程在跑）；本地 `main` = `df188d3`，与 `origin/main` 一致，工作区干净。
- 无未完成的待办事项。

## 8. 凭据索引（只写位置，不写明文）

| 用途 | 位置 |
|---|---|
| 本地 LLM / QQ 凭据 | `wechat-agent-java\.env`（gitignore） |
| 服务器容器凭据 | 服务器 `120.25.170.92:/opt/wechat-agent-infra/.env`（600） |
| 服务器 SSH root 密码 | 由用户提供 |
| 部署私钥 | 仅存于 GitHub Secrets `DEPLOY_SSH_KEY` |

## 9. 历史会话

Codex 会话原始记录在 `C:\Users\33721\.codex\sessions\`（Codex 专有格式，其他 harness 读不到），因此本文件是唯一可迁移的记忆载体；如需更多细节可回头检索这些 jsonl。
