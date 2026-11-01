# 文档索引

> **怎么用**：改代码前先看「现役」里对应的那份；查数据/查历史看「参考」；「存档」只读不维护。
>
> ⚠️ **`docs/` 在 `.gitignore` 里** —— 新增/移动文档必须 `git add -f docs/xxx.md`；核实：`git ls-files docs/`。
>
> 更新原则：**新知识写进对应的专题文档，不要往 `AGENTS.md` 里堆**（它有 64 KB 截断上限）。

## 现役（改代码前必看）

| 文档 | 管什么 | 最后更新 |
|---|---|---|
| [`pitfalls.md`](pitfalls.md) | **踩坑第一步**：103 条坑 + 判定方法 + 修法 | 2026-11-02 |
| [`memory-window-design.md`](memory-window-design.md) | **上下文窗口 / 提取窗口 / STATE 的完整设计**：19 轮、15+2+2、游标防丢、实测数据 | 2026-11-02 |
| [`memory-extraction.md`](memory-extraction.md) | 记忆提取的触发、窗口、成本、写什么不写什么 | 2026-11-01 |
| [`agent-loop.md`](agent-loop.md) | Agent 循环：计划三档、失败分级、反思与重规划、软预算、进度降级 | 2026-10-28 |
| [`llm-call-modes.md`](llm-call-modes.md) | LLM 调用档位、思考模式、缓存计价（含 8 档实测表） | 2026-10-29 |
| [`eval.md`](eval.md) | 记忆与 Agent 评测：用例结构、怎么跑、当前基线 | 2026-10-29 |
| [`todo.md`](todo.md) | 待办与还没做的事 | 2026-10-27 |

## 参考（查数据时看）

| 文档 | 管什么 |
|---|---|
| [`tools-and-prompt-inventory.md`](tools-and-prompt-inventory.md) | 65 个工具与 34 条提示词的真实调用数据、失败率、取舍记录 |
| [`memory-vector-plan.md`](memory-vector-plan.md) | 三层记忆（`memory` / `memory_fact` / `conversation_memory`）的设计与实测 |
| [`self-layer-plan.md`](self-layer-plan.md) | 自主模块「它自己」的设计与实测（主文档） |
| [`ai-abilities.md`](ai-abilities.md) | 看视频 / 画图 / B站评论带图 / 长任务后台跑 |
| [`backup.md`](backup.md) | 备份改版与恢复步骤 |
| [`deploy-security.md`](deploy-security.md) | 部署安全：受限命令、HMAC 验签、CI 供应链 |
| [`channel-robustness.md`](channel-robustness.md) | 通道健壮性：半开连接、重发、限流 |
| [`interaction-feedback.md`](interaction-feedback.md) | 进度条、语音时机、轮内注入、工具按需解锁 |
| [`media-memory.md`](media-memory.md) | 媒体记忆三件套 |
| [`pg-migration.md`](pg-migration.md) | MySQL → PostgreSQL 整库迁移（含 Quartz delegate 坑） |
| [`panel-app.md`](panel-app.md) | 面板安卓壳（APK） |
| [`icp-filing.md`](icp-filing.md) | 备案流程记录 |
| [`rollback.md`](rollback.md) | 回滚步骤 |

## 对外材料（不属于项目文档）

| 文档 | 用途 |
|---|---|
| [`resume-project.md`](resume-project.md) | 简历里这个项目的条目素材（写取舍、数据、验证方式） |

## 存档（已完结，只读）

| 文档 | 是什么 |
|---|---|
| [`archive/plan-2026-10-26.md`](archive/plan-2026-10-26.md) | 2026-10-26 整改方案（已执行完） |
| [`archive/refactor.md`](archive/refactor.md) | 2026-10-27~28 拆上帝类的完整重构记录 |
| [`archive/refactor-summary-2026-10-27.md`](archive/refactor-summary-2026-10-27.md) | 同上，一页纸版 |
| [`archive/self-layer-spec.md`](archive/self-layer-spec.md) | 自主模块早期规格（已被 `self-layer-plan.md` 取代） |
| [`archive/self-layer.md`](archive/self-layer.md) | 自主模块早期说明 |
| [`archive/memory-hybrid-plan.md`](archive/memory-hybrid-plan.md) | 早期混合检索方案（已被向量方案取代） |
| [`archive/schema.sql`](archive/schema.sql) | 迁移前的旧表结构 |
| [`archive/code-analysis-2026-09-02/`](archive/code-analysis-2026-09-02/) | 2026-09-02 旧代码分析（部分结论已过期） |

## 变更日志

- **2026-11-02**：整理文档。`plan-2026-10-26` / `refactor` / `refactor-summary` 进存档；
  `memory-extraction` 里重复的一节压成指针，细节统一到 `memory-window-design.md`。
- **2026-11-01**：新增 `memory-window-design.md`（窗口与 STATE 设计）；`pitfalls` 补第 100、101 条。
- **2026-10-25**：新增 `agent-loop.md`。
