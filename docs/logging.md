# 日志与排障

> 最后更新：2026-11-03
> 一句话：**`docker logs` 只是控制台副本，重建容器就没了；真正的日志在宿主机 `/opt/qq-agent/logs/`。**

## 1. 日志在哪

```
/opt/qq-agent/logs/
├── system/agent.log                 ← 系统级（不含用户会话明细），排障先看这个
├── system/agent.2026-11-02.log.gz   ← 按天压缩，保留 30 天
└── user/<userScope>/agent.log       ← 按用户隔离（userScope 是 openid 的哈希，不是明文）
```

宿主机目录挂进容器的 `/app/logs`（compose `./logs:/app/logs`），**重建容器不影响**。

## 2. 怎么查（三个最常用）

```bash
# ① 今天所有 ERROR / WARN（跨 system 与全部用户）
grep -hE " (ERROR|WARN) " /opt/qq-agent/logs/system/agent.log /opt/qq-agent/logs/user/*/agent.log | tail -50

# ② 某个用户最近 200 行
tail -200 /opt/qq-agent/logs/user/user-<hash>/agent.log

# ③ 某段时间发生了什么（按小时切片）
grep "^2026-11-03 22:" /opt/qq-agent/logs/system/agent.log | grep -vE "DEBUG"
```

或者用仓库里的脚本：

```bash
bash tools/logcheck.sh            # 最近 2 小时的 ERROR/WARN 汇总
bash tools/logcheck.sh 24         # 最近 24 小时
```

## 3. 级别怎么定的

| Logger | 级别 | 理由 |
|---|---|---|
| `com.liche.qqagent` | INFO | 业务日志：工具调用、轮次延迟、提取、定时任务 |
| 根 | INFO | 兜底 |
| `org.quartz` | WARN | 调度器 INFO 太吵 |
| `com.zaxxer.hikari` | WARN | 连接池启动信息无用 |
| `org.springframework.web` | WARN | 每个请求一行，噪音 |
| `dev.langchain4j` | WARN | 框架内部细节对我们无意义 |
| `org.apache.catalina` | WARN | 同上 |

**新增日志的判断标准**：

1. 这条日志**能回答一个具体问题**吗（哪一步慢、哪条失败、为什么跳过）？
2. 出问题时**没有它会不会查不下去**？会 → 加 INFO；不会 → 别加。
3. 会不会**每条消息都打**、且内容几乎一样？会 → 降 DEBUG 或删掉。

## 4. 保留与大小

| 项 | 值 |
|---|---|
| 按天滚动 | 是（`agent.YYYY-MM-DD.log.gz`） |
| 保留天数 | **30 天** |
| 总量上限 | **800 MB**（超了先删最老的） |
| 写盘方式 | 同步（量很小：实测 system 1.9 MB / 用户合计 2.1 MB） |

## 5. 已知的坑

| 坑 | 说明 |
|---|---|
| 只看 `docker logs` | 重建容器后清空，看起来像"日志丢了"——实际在宿主机 |
| 用 `<set>` 掩码验证环境变量 | 空值也会显示成 `<set>`，把"没配置"误判成"已配置"；**要打长度** |
| compose recreate 静默失败 | 输出重定向到 /dev/null 时失败也看不见，容器还用旧配置 | 
