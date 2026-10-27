# 回滚与"每一步可退"（2026-10-27）

> 起因：用户要求"每一步都做好回滚准备，防止前功尽弃"。这份文档就是那条纪律的落地版。

## 一键切镜像

服务器上已装 `/usr/local/bin/qq-agent-rollback`：

```bash
qq-agent-rollback stable                 # 回到稳定版
qq-agent-rollback 761f57c                # 切到某个提交的镜像（短 sha 也行，docker tag 是完整 sha，写全更稳）
qq-agent-rollback                        # 不带参数 = 打印用法
```

它做四件事：

1. 校验 `qq-agent:<tag>` 存在（不存在就列出候选，不动线上）；
2. `AGENT_IMAGE=qq-agent:<tag> docker compose -f docker-compose.remote.yml up -d --no-build agent`；
3. **健康检查：连续 30 次 × 5 秒**，要求 `/api/site/info` 返回 200 **且** 日志出现 `Started QqAgentApplication`；
4. 健康检查失败 → **自动切回切换前的镜像**，并再检查一次；仍失败才报"需要人工介入"。

> ⚠️ 健康检查窗口必须 ≥ 90 秒：应用冷启动实测 **50~57 秒**。第一版写的是 10×3 秒（30 秒），
> 演练时把"正在启动"误判成"挂了"——见 `pitfalls.md` 第 90 条。

## 当前已知良好版本

| 角色 | 镜像 | 说明 |
|---|---|---|
| **stable** | `qq-agent:stable` → `14b69c2` | 2026-10-27 拆分 `ToolRoundExecutor` 后验证通过 |
| 上一个 | `qq-agent:761f57c` | 拒答/占位符修复版 |
| 当前 main | 与 stable 一致 | — |

**每次部署并验证通过后，都要把新镜像打成 stable**（否则 stable 会停留在旧版本）：

```bash
docker tag qq-agent:<新sha> qq-agent:stable
```

## 演练记录（2026-10-27 12:24，实测）

```
qq-agent-rollback 761f57c → ✅ 已切到 761f57c，site=200
qq-agent-rollback stable  → ✅ 已切到 stable，site=200，Started in 50.9s
```

两次切换都在 150 秒内完成，Ks 级请求无异常。

## 代码回滚

```bash
git revert <sha>        # 单独撤掉某一步（保留历史）
git push origin HEAD:main
```
CI 会重新构建并部署（约 8 分钟）——所以**代码回滚是慢路径，镜像回滚是快路径**。
紧急时先用 `qq-agent-rollback` 回到旧镜像，再从容 revert 代码。

## 数据库回滚（重要）

截至 V32，所有迁移都是**只增**（加表 / 加可空列 / 加索引），没有删列、没有改类型：

| 版本 | 内容 | 回滚影响 |
|---|---|---|
| V28 | `memory_fact.origin` | 代码回滚后该列闲置，无影响 |
| V29 | 建 `task_reflection` | 同上 |
| V30 | `memory_fact.source_message_ids` + 回填 | 同上 |
| V31 | 建 `memory_entity` | 同上 |
| V32 | `task_reflection.turn_outcome` | 同上 |

**顺序纪律：先迁移、后部署。** 反过来会因 `ddl-auto: validate` 找不到列而启动失败。
因为都是 additive，**代码回滚不需要回滚 DDL**——旧代码不认识新列也不影响。

## 每一步拆类的固定流程

拆类是"不改行为"的重构，但仍是改动核心路径，所以每一步都按这个走：

1. **只搬代码**，不改逻辑（必要时保留一层转发）；
2. `mvn -q -DskipTests package` 通过；
3. 本地起 pg+redis+app，用 simulator 真跑一轮**带工具的对话**，确认工具执行/回复正常；
4. 提交（**一步一个提交**，commit message 写清搬了什么）；
5. 部署 → 验证 6 项（见下）→ `docker tag 新镜像 stable`；
6. 出问题：`qq-agent-rollback stable` 或 `git revert <这一步的 sha>`。

### 部署后的 6 项验证

```bash
docker inspect -f '{{.Config.Image}}' qq-agent        # 镜像 tag = 目标 sha
curl -sk https://127.0.0.1/api/site/info               # 200
docker logs --since 3m qq-agent | grep "Started QqAgentApplication"
docker logs --since 5m qq-agent | grep -c " ERROR "    # 0
docker logs --since 5m qq-agent | grep "工具注册完成"   # 21 类 / 64 工具
# 面板 /api/admin/tools/stats 能出数（数据库/接口没被打断）
```
