# QQ 通道健壮性（`channel/qq/` 包）

> 2026-09-13 做完并全部实测。原先是 `AGENTS.md` 里的「坑 54」，因为那份文件顶到 64KB
> 上限会被 harness 截断，正文挪到这里，`AGENTS.md` 只留一条摘要。

改动都在 `QqChannel` 及其两个新类（`QqInboundRateLimiter`、`QqProactiveQuota`）里，三件事：
**不再重复发消息 / 主动消息日额度账本 / 入站按用户限流**。

## 一、被动发送失败后的"降级重发"原来是无条件的

`sendPassive` 的 catch 里直接再发一条主动消息。危险的是**结果未知**的失败：读超时、连接中断、5xx
——请求可能已经送达，再发一条就是用户收到两条一模一样的消息。现在按「这次到底有没有发出去」分三种：

1. **响应体里带消息 id**：QQ 其实发出去了、只是响应报错 → 先 `deleteMessage` 撤掉它，再重发。
2. **4xx**（`isDefiniteRejection`：msg_id 失效 / 被动窗口过期 / 令牌失效 / 限频）＝服务端**明确拒收**，
   重发安全。顺带救回「token 被另一个实例顶掉 401」的场景：`recordApiError` 会作废本地 token，重试时拿到新的。
3. **其余**（读超时 / 中断 / 5xx）→ **不重发**，被动回复直接返回 `true`（让 `AgentOrchestrator`
   不要再走标准回复路径，否则等于又发一遍），并累加 `sendResultUnknown` 计数（面板可见）。

**代价**：极少数情况下用户会少收到一条——这是刻意选的，比刷两条好。

**"结果未知"还要再分两类**（断网实测后补）：`Connect timed out` / 连接被拒 / 域名解析失败属于
**连接阶段**失败——一个字节都没送到，重发不可能重复，所以照 4xx 一样**安全重发**（`neverReachedServer`）；
只有 **`Read timed out`**（请求已发出、响应没回来）才是真"结果未知"。踩到的正是这条：掐网后所有发送都是
Connect timed out，原来会白丢一条本来能安全补发的回复。日志现在写明原因（连接未建立 / 服务端明确拒收 /
响应里带消息 id 已撤回）。

## 二、流式回复有同样的坑

正常回复走 `stream_messages`。最终帧失败时原来会 `removeIncompleteStream` 撤掉半截消息、再让 orchestrator
补一条完整回复；但**拿不到 `stream_msg_id` 时（第一帧就超时）根本撤不掉**，QQ 侧可能已经显示了半截内容
→ 用户看到「半截 + 完整」两条。现在 `removeIncompleteStream` 返回「是否**确认**清干净」，
清不干净且这一帧是"结果未知"失败时，按已发出处理（`state.done = true`，抑制补发）。

## 三、主动消息日额度账本

`QqProactiveQuota`（Redis `qq:proactive:<yyyy-MM-dd>`，TTL 2 天）只统计**不带 msg_id 的消息**；
面板「QQ 通道」页显示「今日主动消息 n 条（未设上限/上限 n）」，每条主动发送的日志里也带计数。
`QQ_PROACTIVE_DAILY_LIMIT`（默认 0 = 不限）配成正数后，超限**直接放弃发送并记 WARN**。
Redis 异常不影响发消息（只记一条 WARN，面板显示"不可用"）。

## 四、入站按用户限流

`QqInboundRateLimiter`（固定窗口），`QQ_INBOUND_RATE_LIMIT_PER_MINUTE`（默认 20，0 = 关闭）。
超限时**只回一条礼貌提示**（走被动回复、不占主动配额），其余静默丢弃并累加 `inboundRateLimited`。
目的是防"用户连点刷屏"把每用户串行队列、模型额度和记忆提取全占满。

## 五、顺带修掉的浪费

`handleC2cMessage` 原来**先处理「继续」再记被动窗口**，于是长回复的每一页都走主动消息
（白耗主动配额，配额用光后分页直接发不出去）。现在先 `rememberReplyWindow` 再处理「继续」。

## 配置透传

两个新键已在 compose 里透传（同 `AGENTS.md` 坑 36：**漏了就不生效且无报错**）；
同时补上了 `QQ_API_CONNECT_TIMEOUT_SECONDS` / `QQ_API_READ_TIMEOUT_SECONDS` 的透传
——**这两个是"造出结果未知的发送失败"的唯一办法**（把读超时临时改小），不透传就没法验证上面第 1/3 条。

## 验证记录（2026-09-13 全部实测）

1. 判定逻辑用一次性 harness 全 PASS（限流器判定序列；`neverReachedServer` / `isDefiniteRejection` 13 个用例，
   `Read timed out` 与 5xx 必须 false、4xx 必须 true；harness 在临时目录，不入库）。
2. 面板新指标齐全、部署自检绿。
3. 账本：`POST /api/admin/actions/alerts/notify` → 日志 `send(proactive) ... 今日主动 1 条`、
   Redis `qq:proactive:2026-09-13=1`、面板 `proactiveToday=1`。
4. 入站限流：临时改成 2 条/分钟，前 2 条正常回、其余丢弃且**只回一条**提示（25 字，用户确认收到），计数 4。
5. 断网实测：日志 `被动发送失败（连接未建立，肯定没送达）→ 重发一次主动消息`、`sendResultUnknown=0`
   （没被误判成未知），那条回复**只送达一次**。

### 造断网的排障坑

只掐 `sandbox.api.sgroup.qq.com` 解析出来的两个 IP **不可靠**——JVM 可能用 CDN 的另一个 IP
（`ss -tnp` 里就见过 `14.29.51.120`），于是"掐了网却照样发得出去"，白做一轮测试。要造断网就
**掐全部出站 443**（`iptables -I OUTPUT -p tcp --dport 443 -j DROP`，SSH 与面板入站不受影响），
照旧先安排兜底删除、事后查 `iptables -S OUTPUT | grep DROP`（三次测试都清干净了）。

### 造"半开连接"的正确姿势（从 AGENTS.md 坑 51 搬来，2026-09-15）

半开连接时 OkHttp 的 `onFailure`/`onClosed` **都不会回调**，所以只有自愈能救；但"有没有触发自愈"必须实测。
造法：**只掐那条长连接的入站**（动了 TCP 才会走 `onFailure`）：

1. `ss -tnp state established | grep -i java | grep ':443'` 取本地端口。
   **必须 `grep -vE '^443$'` 排掉 443**——那是面板的入站端口。我漏了这一步，
   一条 `DROP` 把**面板从外部整个封了**（出事后第一件事是 `iptables -S INPUT | grep DROP` 看残留，不是先看日志）。
2. `iptables -I INPUT -p tcp --dport <端口> -j DROP`。
3. 判据：`lastGatewayEventAt` 连续 45 秒不推进才算造成功（网关是多 IP CDN，单个 IP 不算数）。
4. `ss` 里的 `[::ffff:1.2.3.4]` 不能直接喂 iptables，先剥出四段点分。

**纪律**：这类测试用 `setsid nohup` 跑后台（`ssh "bash -s"` 的 stdin 脚本一旦 SSH 断开就可能被带走，
`trap` 清理不保证执行），并**先写好独立的延时兜底清理**。
**状态**：误判已排除（30+ 分钟无 `半开连接` 日志）；**"触发"那一步仍未实测到**（两次都没稳定造出静默条件）。
