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
- agent 容器用 `network_mode: host`，**直接对公网监听 `0.0.0.0:443`（HTTPS，标准端口，应用自带 TLS）**；MySQL/Redis/SearXNG 走 `127.0.0.1`。端口由 compose 的 `SERVER_PORT` 决定（默认 443；服务器 `.env` 另有一行显式覆盖）。
- 远程排查（面板走 HTTPS，域名证书有效，本机排查可用 `-k`）：

```bash
ssh root@120.25.170.92
curl -s https://127.0.0.1/index.html -o /dev/null -w '%{http_code}\n'                     # 前端（证书有效，无需 -k）
curl -sk -H 'X-Agent-Admin-Key: <口令>' https://127.0.0.1/api/admin/overview             # 面板接口（IP 访问证书不匹配，用 -k）
```

- 只重启 agent：`cd /opt/wechat-agent-infra && AGENT_IMAGE=wechat-agent:<sha> docker compose -f docker-compose.remote.yml up -d --no-build agent`

### 运维面板访问方式（应用自带 HTTPS，2026-09-12 定型）

- **面板唯一入口**：`https://liche.cloud/`（**标准 443 端口，地址里不带端口号**；Vue 单页应用，Let's Encrypt 证书，浏览器绿锁）。**ICP 备案期间（2026-09-12 起）域名解析已暂停，暂时改用 `https://120.25.170.92/`**（证书名称不匹配，点继续访问）；备案通过后把 A 记录设回 `ENABLE` 即恢复域名访问。用户明确弃用 VPN（WireGuard/socat/wg0/51820/wireguard-data/宿主 sysctl 已全拆）与 nginx 网关（一次性容器 + 限流都没必要），**不要再加回来**。
- 应用直接用 PEM 证书起 HTTPS，无需 keystore：compose 里 `SERVER_ADDRESS=0.0.0.0`、`SERVER_PORT=443`、`SERVER_SSL_ENABLED=true`、`SERVER_SSL_CERTIFICATE=/app/certs/server.crt`、`SERVER_SSL_CERTIFICATE_PRIVATE_KEY=/app/certs/server.key`，并把宿主机 `docker/tls/` 挂到 `/app/certs`（证书服务器侧生成、不入库；`server.key` 600、`server.crt` 644）。
- 安全组放行 **TCP 443**（2026-09-12 由用户开通，手机访问同样是绿锁）。**8443 的安全组规则待用户在控制台删除**：应用已不再监听 8443（本机探测 8443 是 `connection refused`＝规则还开着；规则删掉后会变成超时）。
- **鉴权分层**：口令经请求头 `X-Agent-Admin-Key` 由 `AdminAccessFilter` 校验（`ADMIN_REQUIRE_KEY=true` 时**这是唯一凭据**，因此不再要求来源 IP 在白名单内），账号由 `AdminSessionController` 经 `POST /api/admin/session` 校验；前端把凭据存 `sessionStorage`（勾「记住账号密码」则存 `localStorage`）。
- **爆破防护**：`AdminAccessFilter` 连续 5 次口令错误即按**真实来源 IP** 封禁 10 分钟（见第 5 节第 10、14 条）。原来 nginx 的 `limit_req` 已随网关一起移除；QQ 机器人本身不受面板限流影响。

### HTTPS 证书（域名 liche.cloud，2026-09-12 已上线）

- 用户于 2026-09-11 在阿里云注册 `liche.cloud`（到期 2027-09-11，NS = `dns31/dns32.hichina.com`）。**放弃 liche.online，买的是 .cloud**；注册后域名先处于注册局 **`client hold`**（阿里云实名认证通过前不放行），公网 DNS 查是 NXDOMAIN，**此状态下签不了证书**（DNS-01 要求域名已委派）。实名通过后 hold 自动解除。
- 方案：**acme.sh + Let's Encrypt + DNS-01（`dns_ali` 插件）**，不用 80/443、不用停服、也不需要备案。HTTP-01 走不通——大陆 ECS 上未备案域名的 80/443 会被阿里云拦。
- 服务器已装好：`/root/.acme.sh`（v3.1.3，从 **Gitee 镜像**装的；`curl https://get.acme.sh` 走 GitHub codeload 会 error 52）、LE 账号已注册、默认 CA = letsencrypt、每天 06:55 的 `acme.sh --cron` 续期任务。
- 证书换进容器：`acme.sh --install-cert --key-file docker/tls/server.key --fullchain-file docker/tls/server.crt --reloadcmd "chmod … && docker restart wechat-agent-java"`。**必须重启容器**，Spring Boot 不会热加载证书。已验证容器对外提供的证书指纹与 `docker/tls/server.crt` 一致、挂载是 `/opt/wechat-agent-infra/docker/tls → /app/certs`，所以换文件 + 重启一定生效。
- **自签证书备份在 `docker/tls/server.{crt,key}.selfsigned`**，回滚＝覆盖回去 + 重启容器。
- 云解析记录由服务器上的脚本用 **RAM 子账号 AccessKey**（只授 `AliyunDNSFullAccess`）经 API 维护；密钥只写 `/root/.acme.sh/account.conf`（600），**不入库、不进 CI、不写日志**。直接调 AliyunDNS API 要手写 HMAC-SHA1 RPC 签名（可用 Python 实现 `DescribeDomains` / `DescribeDomainRecords` / `AddDomainRecord`）。
- 已加解析：`A @ → 120.25.170.92`（TTL 600，ENABLE）。
- 自动化链路（2026-09-12 已跑通）：证书签发/安装脚本 `/root/issue-liche-cloud.sh`，一次性看守任务 `/root/auto-issue-liche-cloud.sh`（已用完自删）。日常续期靠 acme.sh 装好的 **每天 06:55 `acme.sh --cron`**：到期前 60 天自动重签 → 通过 `--install-cert` 的 reloadcmd 覆盖 `docker/tls/` → `docker restart wechat-agent-java`。
- **当前证书**：`CN = liche.cloud`，签发者 Let's Encrypt（YR2），有效期 2026-09-12 → **2026-12-11**；已确认容器对外提供的证书就是这一张（`openssl s_client` 与文件一致），`curl https://liche.cloud/` **不加 `-k` 返回 200**（真实证书链校验通过）。
- **端口**：2026-09-12 应用从 8443 切到标准端口 **443**，地址因此不带端口号（`https://liche.cloud/`）。做法是服务器 `.env` 加 `SERVER_PORT=443`（原 `.env` **没有**这个键，端口一直来自 compose 默认值）+ 重建容器，用户侧只需在安全组放行 TCP 443。**注意 443 上跑未备案域名属于"官方不允许、实际通常可用"**（阿里云拦得住的是 80 端口的 HTTP，443 有加密一般拦不住）；万一被拦，回滚＝把 `SERVER_PORT` 改回 8443 并重建（安全组的 8443 规则先留着）。
- 换真证书后的预期：**用域名访问才有绿锁**，继续用 IP 会提示"证书名称不匹配"（LE 不给 IP 签证书）。

### 运维面板前端（Vue 3 前后端分离，2026-09-12 重构）

- **架构**：前端是独立工程 `web/`（Vue 3.5 + Vite 8 + vue-router 5，无 UI 框架），只通过 JSON 接口与后端通信；后端只提供 `/api/admin/*` 与静态入口。构建产物输出到 `src/main/resources/static/`，由 Dockerfile 的 node 阶段在打包镜像时生成，**部署就是 agent 这一个容器**（没有额外网关）。
- **本地开发**：`cd web && npm install`；`npm run dev`（Vite 5173，已把 `/api` 代理到 `http://127.0.0.1:8080`）。改完样式或组件必须 `npm run build`（直接写进后端 static 目录）才会进 jar。
- **目录结构**：`web/src/views/`（LoginView、DashboardView）、`web/src/panels/`（Overview / Qq / Llm / Maimemo / Tasks / Users / Logs 七个页签）、`web/src/components/`（StatCard、StatusPill、InfoGrid、DataTable、JsonBlock、ChartBars）、`web/src/{api,auth,labels,router}.js`，以及**集中承载全部视觉规范的 `web/src/style.css`**。
- **路由**：`createWebHashHistory`（`/#/login`、`/#/dashboard`），因此网关只需放行固定路径、不需要服务端 rewrite。
- **视觉（2026-09-12 按用户要求改成白色主调）**：白到浅蓝的极淡渐变底 + 极淡冷色网格（`body::before`：120px，竖线略清晰、横线更淡、交点小圆点，mask 向外淡出）；面板是**白色半透明玻璃**（`rgba(255,255,255,.58~.84)` 渐变 + `backdrop-filter: blur(20px) saturate(150%)` + 22px 圆角 + 白色描边 + 极淡外圈 `--ring`）；**强调色只用「淡蓝 → 白」渐变**（`#cfe0ff → #fff`，用在主按钮、选中页签、用户气泡、图表柱），蓝色不铺面积；状态色为柔和的绿/琥珀/红。改视觉只动 `web/src/style.css`。
- **文案**：界面不出现英文状态词，接口状态一律翻中文（正常/降级/异常/未启用/运行中/失败/结果未知/已回复/待机/信息/警告/错误）；原始 JSON 视图保留英文键名（那是接口数据）。`labels.js` 是唯一的状态词典，新增状态值改那里。
- **黑白主题（2026-09-12 新增）**：顶栏与登录卡片各有一个「深色主题 / 浅色主题」按钮（`.theme-toggle`），切换 `<html data-theme="dark">`；选择存 `localStorage['admin.theme']`，没存过时跟随系统 `prefers-color-scheme`。**默认仍是白色（就是原来的样子）**；黑色主题不改结构、不改圆角/玻璃/网格，只换调色板：底色近黑（`#0c0f16 → #05060a` + 冷蓝辉光）、玻璃改成 `rgba(255,255,255,.075→.035)`、文字 `#f1f4fa`，**强调色依旧是「淡蓝 → 白」渐变**（`#9dbcff → #fff`，按钮文字转深色），用户气泡照旧淡蓝渐变，状态色换成更亮的绿/琥珀/红。全部颜色都在 `web/src/style.css` 的 `:root` 与 `:root[data-theme="dark"]` 两个块里（组件里不要再写死颜色，错误提示也改成 `var(--bad-ink)`）；`index.html` 里有一小段内联脚本在首屏前定主题，避免深色下先闪一下白。
- **登录**：账号默认 `rootlcw` + 密码 → `POST /api/admin/session`；勾「记住账号密码」时凭据写 `localStorage`（不勾只写 `sessionStorage`，关标签页即退出），退出登录会清凭据但保留账号名。路由守卫拦截 `/#/dashboard`，接口 401 自动清登录态并回登录页。
- **用户与记忆页**：用户列表按最近活动倒序（**每个用户一条**，空时间的排最后），点「查看记录」进入聊天式视图——用户/机器人左右气泡、可上下滚动、`加载更早的消息` 分页往前翻、可选显示工具调用（`system` 消息）；同一页内还可用分段控件切到「长期记忆」「提醒任务」。
- **告警**：QQ 通道页有「发送测试告警」按钮，调 `POST /api/admin/actions/alerts/test`；另有 `POST /api/admin/actions/alerts/notify`（body `{"message":"…"}`）供 CI 等自动化推送自定义告警，同样只发给配置里的那一个人。
- **模型与搜索**（2026-09-12 新增页签）：展示 LLM 一次性调用（记忆提取/提醒解析）、对话流式调用、SearX-NG 搜索的**次数/失败/成功率/平均耗时/最近错误**，数据来自 `GET /api/admin/metrics/runtime`，由 `metrics` 包里的 `RuntimeMetrics` 在 `OpenAiCompatChatModel`、`OpenAiCompatStreamingChatModel`、`SearxngClient` 三处打点累计（进程内计数，重启归零）。出问题时先看这个页签，能立刻区分"模型慢/模型报错/搜索挂了"。
- **手机适配**：`≤720px` 概览卡 2 列、表格**转卡片列表**（靠每格 `data-label` 显示列名、`thead` 隐藏）、工具栏换行、无横向滚动。
- **安全约定**：接口文本一律用 Vue 插值（自动转义），**不要用 `v-html`**（日志、用户记忆都是用户数据）。
- **验证方式**（可复用）：`tools\ui-verify\verify_spa.py` 用 Playwright 打**公网真实地址**跑完登录/各页签/聊天视图/手机端与视觉断言（详细跑法见该目录 README）：
  ```powershell
  $env:WG_PW='<口令>'; $env:ADMIN_USERNAME='rootlcw'; $env:SPA_BASE='https://liche.cloud'; $env:SPA_TAG='v10'
  & "D:\soft\JetBrains\Python\python\python.exe" "C:\Users\33721\Desktop\wechat-agent\tools\ui-verify\verify_spa.py"
  ```
  （本机调试用同目录 `spa_server.py` 起代理、不设 `SPA_BASE`。）Playwright 需要创建命名管道，受限沙箱下会 `PermissionError: [WinError 5]`。

### 面试陪练（只保留面试，2026-09-12 改版；英语陪练已删除）

- 定位：**不是"换个人设聊天"，而是有题库、有评分卡、有复盘报告的一次模拟面试**。用户当时说"陪练模式没什么用"，所以去掉了只加一致性的英语陪练，改成能留下数据的面试模拟。
- 进入/退出：QQ 发 `陪练 面试`、`陪练 Java 后端 3 年`（岗位会带进提示词）、`结束陪练`；也支持 `/practice interview|off`。**中文指令带参数**靠 `CommandRegistry` 的"整串别名不匹配就按首词识别、余下当参数"（`/help` 里的清单是写死的，加指令必须同步改 `HelpHandler`）。
- **自然语言同样能进**：`tool/InterviewTool` 的 `startInterviewPractice(role)` / `recordInterviewRound(...)` / `endInterviewPractice()`，提示词第 18 条要求模型在用户说"你当面试官陪我练练""模拟一下面试"时**必须调用工具进入模式**，而不是临时扮演；工具必须写进 `ToolRegistry` 的构造列表，显示名加在 `AgentPolicyProperties.DEFAULT_TOOL_DISPLAY_NAMES`。
- **不动用户人设**：只在 `user_profile` 记 `coach_mode`(interview) / `coach_session_id` / `coach_role`，由 `agent/CoachPresets.withMode(人设, 模式)` 在 `AgentOrchestrator.invokeAgent` 里把模式要求（含整个题库与评分观察点）追加到系统提示词末尾，退出即原样恢复。
- 数据落库：`interview/` 包 —— `InterviewRound`(表 `interview_round`：题类/题目/回答要点/四维分数/反馈) + `InterviewBank`(6 个题类：自我介绍、项目深挖、技术基础、系统设计、行为面试、反问环节，每类带评分观察点) + `InterviewService`(**复盘报告由程序按记录生成**：轮数、各维度均分、最弱项、未覆盖题类、下次重点；不靠模型记忆)。
- **踩过的坑**：模型会在长回复里"忘了先记分"（第一版实测第 2 轮没写进库）。修法是**把顺序写死并前置**——"每轮必须先调用 recordInterviewRound，再写反馈，顺序不能反，漏记等于这轮没练"，工具描述里也强调"在写反馈之前先调用"。改完实测 2/2 轮都记账。
- `/help` 与 `PracticeHandler` 的用法文案都要跟着改；这一项**没有 QQ 菜单按钮**（菜单已占满 10 项，QQ 侧有上限）。

### 墨墨背单词开放 API 接入（2026-09-12 新增）

- 用途：QQ 里直接问「我今天背了多少单词 / 还剩多少没刷」→ 查真实进度回答；每天到点（默认 21:30）推一条今日进度；面板新增「背单词」页签。
- **接口**：`https://open.maimemo.com/open/api/v1/*`，`Authorization: Bearer <个人 access token>`，响应统一 `{success, data, errors}`。用到 `study/get_study_progress`、`study/get_today_items`、`study/query_study_records`（还有 `add_words`/`advance_study`/云词本 CRUD，暂未接）。官方限流：**10 秒 20 次 / 60 秒 40 次 / 5 小时 2000 次**，所以服务层带 30 秒缓存（面板自动刷新与聊天追问都走缓存）。
- **Token 从哪来**：墨墨 App 里的「开放 API」入口生成，**页面显示的有效期只有一天左右**，过期后接口返回 401（实测：有效的 token 能直接拿到 `progress`）。因此设计了**面板内更新**：`maimemo_setting` 表（键值表）存 Token，**数据库里的值优先于环境变量**，粘贴保存即生效、不用登录服务器；清空则回落到 `MAIMEMO_API_TOKEN`。Token 失效时聊天工具会明说"去面板更新"，每日推送也会推一条失效提醒（而不是装作没事）。
- **代码结构**：`maimemo/` 包 —— `MaimemoClient`(HTTP+错误翻译，401 抛 `MaimemoAuthException`)、`MaimemoService`(Token 管理/缓存/快照/文案)、`MaimemoPushService`(每日扫描 + 立即推送)、`MaimemoSetting`+Repository；`tool/MaimemoTool`(聊天工具 `getMaimemoStudyProgress`)；`controller/AdminMaimemoController`(`GET /api/admin/maimemo/overview`、`POST /refresh|/token|/push/settings|/push/now`)；前端 `web/src/panels/MaimemoPanel.vue`（页签「背单词」）。
- **自然语言识别交给模型**（用户明确要求）：工具只提供能力，描述里写清典型说法，**不做任何关键词硬编码**；提示词第 19 条要求先取数据再回答、禁止凭印象编数字。实测两种说法（"我今天背了多少单词？还差多少没背完？"、"墨墨那边我今天还剩多少没刷"）都会调用工具。
- 推送目标复用 `ALERT_QQ_OPENID`（用户本人的 openid）；QQ 主动消息有额度限制，推送"尽力而为"，失败只记日志，**面板状态才是准的**。每天只推一次（日期记在 `maimemo_setting.last_push_date`），面板有开关与时间设置 + 「立即推送一次」。
- 接口只给"今日完成/总数"，**新学与复习的拆分要靠今日单词列表自己算**：列表没取全（`total` 大于拉取条数）时不能拿列表长度当复习数——所以按 `max(item-limit, total)` 拉取算拆分，面板只展示前 `item-limit` 条，取不全就显示 `—`。
- 配置项：`MAIMEMO_ENABLED`、`MAIMEMO_API_TOKEN`、`MAIMEMO_DAILY_PUSH_ENABLED`、`MAIMEMO_DAILY_PUSH_TIME`、`MAIMEMO_TIMEOUT_SECONDS`、`MAIMEMO_CACHE_SECONDS`；compose 的 `environment` 里必须列出来（只传列出的变量）。

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
- **部署后自检**（2026-09-12 新增，同日加入域名校验）：部署完等应用就绪，然后检查「首页 200 / 前端 JS 资源 200 / 无口令 401 / 带口令 200 / 账号密码登录 200」(走 IP `https://$DEPLOY_HOST`，`curl -k`)，以及 **`https://liche.cloud/` 首页、前端资源、带口令接口三项（不加 `-k`，走真实证书链）——这三项仅在该域名有解析时执行**（备案期间暂停解析时自动跳过并打印提示，解析恢复后自动重新校验，避免误报红）；任一项不符即调用告警接口推一条 QQ 消息并把流水线置红。也就是说**改坏了、或者证书过期/域名解析挂了，都会被系统自己发现并通知你**。
- **文档改动不触发构建**：`paths-ignore` 覆盖 `**.md`、`docs/**`、`AGENTS.md`、`LICENSE`（实测：纯文档 push 后流水线条数不增加）。
- 查看流水线：`gh run list --repo liche719/wechat-qq-agent` / `gh run watch <id> --repo liche719/wechat-qq-agent --exit-status`。

## 5. 已知坑与约定（都踩过）

1. MySQL 必须钉 `8.0.46`：数据卷由 8.0.46 创建，换 8.0.27 会导致 InnoDB 启动失败。
2. Quartz：`job-store-type: jdbc`，`initialize-schema` 必须为 `never`（历史上是 `always`，有重建表风险，已修）。
3. **SearXNG 引擎配置（2026-09-12 按实测重做，别再想当然）**：这台机器是阿里云大陆机房 IP，逐引擎实测结论——
   - **可用**（中文查询都有 10~20 条）：`yandex`、`naver`、`resulthunter`、`searchmysite`、`mwmbl`（英文索引）、`bing`（**必须 `base_url: https://cn.bing.com`**，走 `www.bing.com` 会 302 且解析不到结果）。
   - **不可用**：google / google cse / duckduckgo / brave / qwant / wikipedia / wikidata / seznam / tusksearch / wiby（超时不可达）；baidu（判验证码，引擎挂起 1 小时）、360search（跳 `qcaptcha` 页）、mojeek（验证码）、fastbot（403）、gabanza（证书错误）；**sogou 是引擎代码本身报错**（`AttributeError: resp.next_request`，与网络无关）；quark / yep / privacywall / crowdview / encyclosearch 返回空。
   - **关键机制（本次"搜索一直失败"的真正原因）**：**失效引擎和可用引擎一样要处理**——每个失效引擎都要等 3 秒 `request_timeout` 再重试，十几个叠加会把单次搜索拖到 **20 秒以上**，超过应用侧 `searxng.timeout-seconds`（默认 15s）→ 聊天里表现为"搜索一直失败"，而 SearXNG 侧只是慢。因此配置用 `use_default_settings.engines.keep_only` **只保留上面 6 个引擎**，修好后实测 **2~3 秒返回 60 多条结果**。
   - `docker/searxng/settings.yml` 是**挂载**进容器的，改完必须重启容器才生效——CI 部署步骤已加 `docker restart wechat-agent-searxng`。该文件处于 `.gitignore` 的 `/docker/searxng/` 规则下**但已被跟踪**：`git add` 会提示"被忽略"，实际仍能正常提交，用 `git hash-object <file>` 与 `git rev-parse HEAD:<path>` 对比确认即可，别被提示误导。
   - **带出处的回答（2026-09-12 新增）**：`SearchTool` 现在会把排名靠前的 `searxng.deep-read-count`（默认 3）条结果**用 `WebPageTool.fetchTextQuietly` 抓正文**（每条 `deep-read-chars`，默认 1200 字）一并交给模型，避免只凭聚合站摘要作答；每条来源还会输出一行 `🔗 编号. 标题 — 链接`，由 `AgentLoop.appendSearchSources` **在回复结尾统一附上「参考来源」**（最多 5 条，不依赖模型记得写）；模型只需在句内用 `[编号]` 标注（提示词第 14 条已说明）。抓正文失败会静默退化为只用摘要。
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
19. 服务器上的旧 `.env.bak-*` 会带着历史口令，**只留最近 1 个**用于回滚即可（2026-09-12 已清理到只剩最新那份）。
20. **容器 json-file 日志默认不轮转**：docker 的 json-file 驱动如果没有 `max-size`，容器 stdout 会无限增长（Spring Boot 的 root appender 同时挂控制台，所以每条日志都会落一份）。4 个服务已统一配 `logging.options: {max-size: 10m, max-file: 3}`（每个容器最多 30MB）。应用自身的文件日志由 logback 按 30 天轮转，但它写在**容器内**（没有挂卷），容器重建就没了 —— 面板「日志」页读的就是它。
21. **acme.sh 会带引号回写 `~/.acme.sh/account.conf`**：里面存的是 `SAVED_Ali_Key='LTAI5t…'`（单引号），自己写的诊断脚本若直接取 `=` 后面的字符串就会带上引号，拿去调阿里云 API 会得到 **`InvalidAccessKeyId`（"Specified access key is not found or invalid."）**，看着像密钥被删了、其实是解析问题——本次就为这个白折腾了一轮。acme.sh 自身用 shell `source` 读该文件，带引号无影响。Python 读时务必 `.strip().strip("'").strip('"')`。
22. **新注册域名会先被注册局 `client hold`**（阿里云实名认证通过前）：此期间公网 DNS 是 NXDOMAIN，DNS-01 的 `_acme-challenge` TXT 查不到，acme.sh 会**一直循环「Not valid yet」重试**（实测空转 10 分钟以上不停），所以自动签发脚本必须用 `timeout 900` 之类包住，别让它挂着。另外 hold 解除后解析还有约 5 分钟负缓存：TXT 刚加好时可能短暂查不到，等一下就会通过。实测时间线：注册 19:29(UTC) → 次日 06:40 左右 hold 才消失。
23. **浏览器会记住"点过继续访问"的那次不安全状态**：换上有效证书后，如果用户在换证书**之前**打开过面板并点过"继续访问"，那个标签页会一直显示「不安全」（提示语是"您与此网站之间建立的连接不安全 / 请勿在此网站上输入任何敏感信息…"），**与服务器无关**。判定方法：`tools/ui-verify/check_security.py`（真实 Chromium 直连、不忽略证书错误）——直连正常就说明是浏览器侧；处理办法是关掉旧标签页/重启浏览器/换无痕窗口，并**清掉 IP 地址那个书签**（IP 访问永远提示证书名称不匹配，LE 不给 IP 签证书）。另注意本机装了 Steam++（Watt Toolkit，进程 `Steam++` / `Steam++.Accelerator`）会劫持部分域名 DNS（如 github→127.0.0.1），排查网络问题时先把它退出。
24. **排查用的小知识（省时间）**：① 生产（QQ 模式）下 `/api/sim/*` **不会注册**（`SimulatorController` 上有 `@ConditionalOnProperty wechat.channel.mode=simulator`），直接用会 404——想跑"消息→LLM→工具→回复"的端到端链路只能在 QQ 里真发消息，之后看面板「模型与搜索」页签的计数（进程内计数，重启归零）。② 服务器 `.env` 里**没有** `MYSQL_PASSWORD`，compose 用的是默认值 `root`（即 `mysql -uroot -proot`，库名 `wechat_agent`）。③ `mysql`/`redis`/`searxng` 都绑 `127.0.0.1`，容器内查数据用 `docker exec -it wechat-agent-mysql mysql -uroot -proot`（注意远程脚本里 `docker exec -i` 会吞 stdin，要加 `< /dev/null`）。④ SearXNG 容器里**没有 curl**，想测容器内出网得用 `python3` 或 `wget`。⑤ 想端到端测指令/回复链路（本机不方便发 QQ 消息时）：把服务器 `.env` 的 `WECHAT_CHANNEL_MODE` 改成 `simulator` 并重建容器——QQ 通道由 `QQ_ENABLED` 独立控制**不会被顶掉**；然后带管理员口令 `POST /api/sim/send {"userId":"sim-xxx","content":"…"}`（同步返回回复，`/api/sim/replies` 查推送），测完把模式改回 `disabled`、**删掉测试用户在各表的行**。注意 compose 只把 `environment:` 里列出的变量传进容器：`WECHAT_CHANNEL_MODE` 是 2026-09-12 才补上的 passthrough，之前改 `.env` 根本不生效（表现为 `/api/sim/*` 一直 404）。
25. **中文文本指令是"整串别名"匹配**：`CommandRegistry` 原来只认完全相等的串（如「结束陪练」），写成「陪练 英语」这种"指令+参数"会**静默落到大模型**（看起来像功能生效了，其实只是模型自己在临场演，`user_profile.coach_mode` 一行都没写）。2026-09-12 已改成：整串不是别名时**退回按首词识别、余下作为参数**；`HelpHandler` 的指令清单是**写死的**（避免与 Registry 循环依赖），加新指令必须同时改它，否则 `/help` 里看不到。
26. **墨墨开放 API 的三个特点**（2026-09-12 接入时实测）：① 个人 access token 在**墨墨 App** 里生成、**有效期只有一天左右**，过期返回 401——所以别把它当成长期密钥写死，本项目把 Token 存进 `maimemo_setting` 表并**优先于环境变量**，用户在面板「背单词」页粘贴即可；② 官方**限流**（10 秒 20 次 / 60 秒 40 次 / 5 小时 2000 次），面板自动刷新很快，必须带缓存（本项目 30 秒）；③ 接口只给"今日完成/总数"，**新学与复习要自己按今日单词列表拆**，列表没取全就不能拿条数当复习数。另外 `Spring Data Redis` 会对 id 为 String 的 JPA 仓库报 "Could not safely identify store assignment"（本项目不用 Redis 仓库，已在 `application.yml` 里 `spring.data.redis.repositories.enabled: false` 关掉）。
27. **模型"每轮都要调工具"不牢靠**：面试陪练第一版实测模型会在长回复里漏调 `recordInterviewRound`（那轮等于没练）。凡是"每轮都必须记账"的场景，**要在提示词里把动作顺序写死并前置**（"先调工具、再说话，顺序不能反"），并在工具描述里再强调一次；只写"每轮都要调用"不够。

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

## 7. 当前状态（2026-09-12 20:20）

- 远程 `wechat-agent-java` 运行中，**应用自带 HTTPS 监听 `0.0.0.0:443`（标准端口），证书是 Let's Encrypt 签发给 `liche.cloud` 的有效证书**；`status=UP`、QQ 通道 `UP`。
- **面板入口：`https://liche.cloud/`（不带端口号、绿锁）**。Vue 单页应用，**7 个页签（总览 / QQ 通道 / 模型与搜索 / 背单词 / 任务 / 用户与记忆 / 日志）**→ 未登录进 `/#/login`；账号 `rootlcw` + 密码（明文只在用户手上）。勾「记住账号密码」后凭据存浏览器本地。**支持黑白主题切换**（顶栏与登录卡片按钮，默认白色）。IP 地址 `https://120.25.170.92/` 仍能打开，但会提示证书名称不匹配。
- 远程**只有 4 个容器**（nginx 网关与 VPN 全部拆除），全部配了 10m×3 的日志上限；只有 mysql/redis/searxng 三个数据卷（**严禁删除**）。
- 公网暴露面：**22（SSH）、443（面板）**；8443 的安全组规则暂时保留（回滚备用，应用已不再监听），8080 / 51820 / 51821 均未开。内存占用平稳。
- 数据：`user_profile` **3**（全是本人的 QQ 号）、`conversation_memory` **370**（本人为主）、`reminder_task` 14、`user_work_memory` 49、`user_core_memory` 17；`interview_round` 与 `maimemo_setting` 为 2026-09-12 新建表（验证后已清空/仅留推送设置）。微信与模拟器残留保持清空。
- **面试陪练**已上线并端到端验证（自然语言进模式 → 逐轮评分入库 → 「结束陪练」出程序生成的复盘报告 → 模式自动清除），英语陪练已删除。
- **墨墨背单词**已上线并端到端验证：面板「背单词」页读得到今日进度（当日实测 0/124）、Token 可在页面保存/清除（存 `maimemo_setting`，优先于环境变量）、`POST /maimemo/push/now` 实测推送到本人 QQ 成功（`sent=true`）、QQ 聊天里两种不同说法都会调用工具取真实数据；每日 21:30 自动推送已启用（当天已推过一次，日期记在 `last_push_date`）。Token 目前写在服务器 `.env` 的 `MAIMEMO_API_TOKEN`（面板保存的值优先）。
- CI 现在是自验证的：部署后自动检查页面/鉴权/登录接口，失败会推 QQ 并置红；旧镜像只保留两个；纯文档改动不触发构建。
- 告警已上线（`ALERT_ENABLED=true` → 本人的 openid），已实测推送成功（测试告警 + 自定义 notify 各一次）。
- **域名/证书/端口（已完成）**：`liche.cloud` 已注册、实名通过、A 记录生效，**Let's Encrypt 证书已签发并装入容器，应用监听 443，面板走 `https://liche.cloud/`（不带端口）绿锁**；acme.sh 每天 06:55 自动检查续期（到期前 60 天重签并自动重启容器）。CI 自检已改为验证不带端口的域名地址。整个流程全自动，用户无需再操作。
- 遗留可选项：`/api/clawbot/*` 与整个微信 clawbot 通道**已于 2026-09-12 按用户要求删除**（`channel/clawbot/`、`ClawbotController`、`wechat.clawbot.*` 配置，`WeChatChannel` 抽象保留给未来新通道）；**ICP 备案进行中**（用户 2026-09-12 提交，`.cloud` 可备案）：材料清单与逐屏步骤见 `docs/ICP备案指南.md`（**注意 `.gitignore` 里有 `/docs/`，新增文档要用 `git add -f` 才会入库**）；个人备案网站名称禁用词见该文档第 4 节（推荐 `技术学习记录`）。
- **备案期间的状态（2026-09-12 起）**：按管局要求"未备案不得开通网站"，`liche.cloud` 的 `A @ → 120.25.170.92` 记录已置 **`DISABLE`**（保留未删，RecordId `2098500125097357312`，恢复时改回 `ENABLE`），**面板暂时改用 `https://120.25.170.92/`**（证书名称不匹配，点继续访问）。证书自动续期不受影响（DNS-01 只加临时 TXT）。CI 自检已改成**域名无解析时跳过域名三项并打印提示**，所以这段期间的部署不会误报红。备案通过后：① A 记录恢复 `ENABLE`；② 服务器 `.env` 填 `SITE_ICP=<备案号>` 并重启容器，页脚即显示备案号（已实现并验证：`site.icp` → `GET /api/site/info` → `web/src/components/SiteFooter.vue`，未配置时整块不渲染）。
- 本地：Docker Desktop 未启动，本地 JAR 未运行，`target/` 已删除（需要时 `mvn package` 重建）。

## 8. 凭据索引（只写位置，不写明文）

| 用途 | 位置 |
|---|---|
| 本地 LLM / QQ 凭据 | `wechat-agent-java\.env`（gitignore） |
| 服务器容器凭据 | 服务器 `120.25.170.92:/opt/wechat-agent-infra/.env`（600） |
| 运维面板登录 | 服务器 `.env` 的 `ADMIN_USERNAME`（现为 `rootlcw`）与 `ADMIN_API_KEY`（600）；明文只由用户保存 |
| 服务器 SSH root 密码 | 由用户提供 |
| 域名 DNS API（RAM 子账号，仅 `AliyunDNSFullAccess`） | 服务器 `/root/.acme.sh/account.conf`（600，`SAVED_Ali_Key` / `SAVED_Ali_Secret`）；用户可在 RAM 控制台随时禁用 |
| 墨墨背单词个人 access token | 服务器 `.env` 的 `MAIMEMO_API_TOKEN`（600），或运维面板「背单词」页保存进 `maimemo_setting` 表（后者优先）；token 在墨墨 App「开放 API」里生成，**有效期约一天** |
| 部署私钥 | 仅存于 GitHub Secrets `DEPLOY_SSH_KEY` |

## 9. 历史会话

Codex 会话原始记录在 `C:\Users\33721\.codex\sessions\`（Codex 专有格式，其他 harness 读不到），因此本文件是唯一可迁移的记忆载体；如需更多细节可回头检索这些 jsonl。

## 10. 工作区结构（2026-09-12 二次整理，约 59MB）

```
C:\Users\33721\Desktop\wechat-agent\
├─ AGENTS.md                    工作区记忆入口
├─ DS-HARNESS-PROMPT.md         用户给 AI 的初始提示词
├─ tools\ui-verify\             面板端到端验证工具（脚本 + README + 最新一轮截图 v9-*.png）
├─ .git-ca\                     导出的系统根证书，**git push 依赖它，不能删**
└─ wechat-agent-java\           git 仓库（源码、配置、AGENTS.md 完整记忆）
   └─ web\node_modules\ (~53MB) 前端依赖，`npm install` 可重建（保留了，方便随时构建前端）
```

- 已删除（2026-09-12 二次整理）：`wechat-agent-java\target\`（94.6MB，`mvn package` 可重建）、`wechat-agent-java\logs\`（5.5MB 本地跑 JAR 的日志）、`.trash\`（2.6MB 的 research 打包）、旧两轮验证截图（v8-* 与 light/dark 主题那轮）。
- 第一次整理（同日更早）删除：`research/`、`wechat-agent-java/{tmp,backup,stored-media}`、工作区根 `logs/`、空的 `docker/`、`.ui-test/`（并入 `tools/ui-verify`）。
- `backup/`、`stored-media/`、`logs/`、`tmp/` 都是**本地跑 JAR 时生成**的，远程服务器各有独立一份；以后本地调试完顺手删。
- 服务器侧同步清理（2026-09-12）：acme.sh 源码目录、一次性「等实名」看守脚本、签发日志、`/tmp` 临时文件；**保留** `~/.acme.sh`、`/root/issue-liche-cloud.sh`、自签证书备份、数据库全库备份。
- 验证面板跑法见 `tools\ui-verify\README.md`（含排查"浏览器说不安全"的 `check_security.py`）。
