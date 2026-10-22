# docs 索引

这里是**细节文档**。`AGENTS.md` 刻意保持在 64 KB 以下（harness 读取上限，超了尾部会被静默截断），
所以"每次都要用的"在 `AGENTS.md`，**"查的时候才用的"在这里**。

> ⚠️ **`docs/` 在 `.gitignore` 里** —— 新增/移动文档必须 `git add -f docs/xxx.md`。
> 核实是否真的入库：`git ls-files docs/`。

## 先看哪个

| 你在干什么 | 看哪篇 |
|---|---|
| **踩到坑 / 要动不熟的模块** | [`pitfalls.md`](pitfalls.md) ← **78 条**完整坑，开头有按主题的索引 |
| 想知道还有什么事没做 | [`todo.md`](todo.md) |
| 动工具集或系统提示词 | [`tools-and-prompt-inventory.md`](tools-and-prompt-inventory.md) |
| **改进度条 / 语音时机 / 轮内注入 / 按需工具** | [`interaction-feedback.md`](interaction-feedback.md) |
| 动记忆（`memory` / `memory_fact` / `conversation_memory`） | [`memory-vector-plan.md`](memory-vector-plan.md) |
| 动记忆提取的触发/窗口/成本 | [`memory-extraction.md`](memory-extraction.md) |
| 动 LLM 调用档位、思考模式 | [`llm-call-modes.md`](llm-call-modes.md) |
| 动自主模块「它自己」 | [`self-layer-plan.md`](self-layer-plan.md) |
| 动部署 / CI / 密钥 | [`deploy-security.md`](deploy-security.md) + `pitfalls.md` 的「部署与 CI」 |
| 动备份 | [`backup.md`](backup.md) |
| 动通道、重发、主动消息 | [`channel-robustness.md`](channel-robustness.md) |
| 动媒体记忆 | [`media-memory.md`](media-memory.md) |
| 看视频 / 画图 / 评论带图 / 长任务后台跑 / 花费分类 | [`ai-abilities.md`](ai-abilities.md) |
| 动数据库结构 / 迁移 | [`pg-migration.md`](pg-migration.md) + `AGENTS.md` §5 硬约定 |
| 动面板前端 | `AGENTS.md` §3「运维面板前端」 |
| 面板安卓壳（APK） | [`panel-app.md`](panel-app.md) |
| 备案相关 | [`icp-filing.md`](icp-filing.md) |

## 现役文档（16 篇）

**「最后校对」= 最后一次逐篇对齐过代码/配置/生产事实的日期**（不是文件 mtime）。
**2026-10-22 整理**：新增 `interaction-feedback.md`；三篇存档与两份历史 SQL 挪进 `archive/`，
根目录只留现役。

| 文档 | 管什么 | 最后校对 | 内容日期 |
|---|---|---|---|
| [`pitfalls.md`](pitfalls.md) | **78 条已知坑与约定**（原文 + 主题索引 + 带日期的现状括注） | 2026-10-20 | 2026-09-11 ~ 10-20 |
| [`interaction-feedback.md`](interaction-feedback.md) | **进度条 / 语音时机（`atEnd`）/ 轮内注入 / 按需工具 / 搜索条数 / 合并窗口** | 2026-10-22 | 2026-10-22 |
| [`tools-and-prompt-inventory.md`](tools-and-prompt-inventory.md) | 工具与提示词的真实调用数、失败率、取舍记录。**§0~§7 是历史快照，最新现状看 §8**（64 个工具 / 21 个类 / 30 条规则） | 2026-10-22 | 2026-10-09 ~ 10-22 |
| [`todo.md`](todo.md) | 待办与「说过但没做」的事 | 2026-10-20 | 2026-10-20 |
| [`memory-vector-plan.md`](memory-vector-plan.md) | 记忆三层合并、向量召回、归档删除的完整设计与实测 | 2026-10-10 | 2026-09-17 ~ 09-20 |
| [`memory-extraction.md`](memory-extraction.md) | 提取触发（轮次驱动）、窗口、落库埋点口径、成本账 | 2026-10-10 | 2026-09-14 ~ 10-08 |
| [`llm-call-modes.md`](llm-call-modes.md) | 调用档位、思考模式、**前缀缓存计价**、审计表 | 2026-10-10 | 2026-09-13 ~ 10-09 |
| [`self-layer-plan.md`](self-layer-plan.md) | 自主模块「它自己」的规格、实现、实测与坑（主文档） | 2026-10-20 | 2026-09-14 ~ 10-20 |
| [`backup.md`](backup.md) | 备份改版（每天一个 zip、媒体共享存）与**恢复步骤** | 2026-10-10 | 2026-09-13 ~ 09-20 |
| [`media-memory.md`](media-memory.md) | 媒体记忆三件套（读上限、写回描述） | 2026-10-10 | 2026-09-13 |
| [`channel-robustness.md`](channel-robustness.md) | 半开连接自愈、重发三分支、限流、造半开的正确姿势 | 2026-10-10 | 2026-09-13 |
| [`deploy-security.md`](deploy-security.md) | 受限部署命令、HMAC 验签、CI 供应链、**分片上传** | 2026-10-10 | 2026-09-13 ~ 09-30 |
| [`pg-migration.md`](pg-migration.md) | MySQL → PostgreSQL 整库迁移全过程（历史事件，结论仍有效） | 2026-10-10 | 2026-09-17 ~ 09-20 |
| [`panel-app.md`](panel-app.md) | 面板安卓壳 `liche-panel-1.0.apk`（产物在**工作区根**，不在仓库） | 2026-10-10 | 2026-09-29 |
| [`icp-filing.md`](icp-filing.md) | 备案流程记录（**已通过**：粤ICP备2026141530号-1） | 2026-10-10 | 2026-09-12 |
| [`ai-abilities.md`](ai-abilities.md) | 看视频 / 画图 / B站评论带图 / 长任务后台跑 | 2026-10-21 | 2026-10-21 |

## 存档（不再维护，仅追溯用）

搬进 `archive/` 是为了让根目录一眼看清"哪些还该看"。**内容保持原样，不删不改**——
它们记录的是当时的决策与踩坑过程，有追溯价值。

| 文档 | 为什么存档 |
|---|---|
| [`archive/memory-hybrid-plan.md`](archive/memory-hybrid-plan.md) | 早期混合检索方案，已被 `memory-vector-plan.md` 的向量方案取代 |
| [`archive/self-layer-spec.md`](archive/self-layer-spec.md) | 自主模块早期纸面规格；现役是 `self-layer-plan.md` |
| [`archive/self-layer.md`](archive/self-layer.md) | 自主模块早期骨架说明；同上 |
| [`archive/schema.sql`](archive/schema.sql) | **已作废**：MySQL 时代 DDL 快照，照着建库会建错。建库看 `deploy/postgres/V11~V19*.sql` + `schema-generated.sql` |
| [`archive/migrations/20260901-memory-evolution.sql`](archive/migrations/20260901-memory-evolution.sql) | **已作废**：2026-09-01 的 MySQL 迁移；两张旧记忆表已被 `V17` 删 |

## 约定

- **坑号是引用契约**：全项目代码注释和文档都按「坑 38」「坑 52」引用，所以 `pitfalls.md` 的编号
  **只能往后追加，不能重排、不能插号**（当前末条 **78**）。
- **不要存会腐坏的数字**：表行数、花费、命中率这类快照别写进文档正文；写"去哪看实时数字"。
  真要记，就带明确日期，并说明它的时间窗。
- **不删历史**：被取代的方案标「存档」并写清哪个是现役，不要直接删——很多结论是花时间换来的。
  存档件统一放 `archive/`，根目录只留现役。
- **新增文档**：先想清楚它是不是该并进某一篇；真要新开，同时更新本索引与 `AGENTS.md` §6 的速查表。
