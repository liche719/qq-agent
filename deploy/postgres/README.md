# deploy/postgres —— 生产库结构（PostgreSQL 16 + pgvector）

生产跑 `ddl-auto: validate`：**Hibernate 只校验、不建表**。所以新表/新列必须先在这里补一份 SQL，
并**在部署前对生产库执行**，否则容器起不来（历史事故见 `docs/pitfalls.md` 的"部署前建表"）。

| 文件 | 用途 |
|---|---|
| `V11` ~ `V27` | 按版本号排序的结构变更。V11 是迁到 PostgreSQL 后的起点；V17 删掉了 MySQL 时代的两张旧记忆表 |
| `schema-generated.sql` | 从实体生成的**结构快照**，用来对照"代码期望什么"；不直接执行 |
| `data-20260918-*.sql` | 一次性数据修正（迁移当天用的），**不要重复执行** |
| `migrate_mysql_to_pg.py` | 2026-09-18 整库迁移脚本（历史件，只读保留） |

约定：

- **新变更往后编号**，不要改已发布的文件——生产可能已经执行过。
- 加列优先 `ADD COLUMN IF NOT EXISTS`；删表/删列前先确认没有代码引用（`grep` 实体字段）。
- 改完同步 `docs/pg-migration.md` 与 `AGENTS.md` §5 的硬约定。
