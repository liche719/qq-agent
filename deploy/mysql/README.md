# deploy/mysql —— 历史脚本（已退役，不要执行）

MySQL 已于 **2026-09-20 退役**，生产改用 PostgreSQL（见 `docs/pg-migration.md`）。
这里的 `V2` ~ `V10` 是 MySQL 时代的建表脚本，**只作为历史记录保留**：

- 现在照着它建库会得到和代码不一致的结构；
- 现役结构看 `deploy/postgres/`；
- 本地 `docker compose` 里的 mysql 服务也只是历史残留（应用连的是 PostgreSQL）。

保留原因：追溯"某张表最初是谁建的、为什么这么设计"时有价值。
