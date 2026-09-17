# MySQL → PostgreSQL 迁移记录（2026-09-17/18）

## 结果

**生产已于 2026-09-18 00:15 切到 PostgreSQL 16.14 + pgvector**，一次成功：

| 验证项 | 结果 |
|---|---|
| 容器 env | `DB_URL=jdbc:postgresql://127.0.0.1:5432/wechat_agent`、`DB_DRIVER=org.postgresql.Driver`、`DB_USER=wechat_app`、`QUARTZ_DELEGATE=PostgreSQLDelegate` |
| 应用连在 pg 上 | `pg_stat_activity` 里 3 条 `PostgreSQL JDBC Driver` 空闲连接 |
| 已离开 MySQL | MySQL 上 `wechat_app` 的连接数 = **0** |
| 启动 | `Started ... in 44.077 seconds`（`production` + `validate`，无 schema 错误） |
| 面板 | `/overview`、`/users`、`/memory/overview` 全 200，`qq: UP` |
| 数据 | conversation_memory 1160 / core 29 / reminder 20 / exam_plan 1 与 MySQL 一致 |
| 备份 | 容器里 `pg_dump 18.6` 可用、能出 dump（每晚 03:00 会自动用它） |

## 迁移前演练（同一套脚本）

本地与服务器各跑一遍：**30/30 表、1702 行、逐表行数一致、NULL 抽查一致、`bit(1)`→boolean 正确**。
脚本：`deploy/postgres/migrate_mysql_to_pg.py`；schema：`deploy/postgres/schema-generated.sql`（由 Hibernate 从实体生成）。

## 踩过的坑（都在这份记录里，别再踩）

1. **3 个实体写死 MySQL 的 `LONGTEXT`** → PG 不认。改成可移植 `@JdbcTypeCode(SqlTypes.LONGVARCHAR)`
   （MySQL 仍 longtext、PG 为 `varchar(32600)`；content 上限 12000 字，余量够）。
2. **`BigDecimal` 默认 `numeric(38,2)`** → 在 PG 上把 `0.0568 元` 变成 `0.06`（钱失真）。
   钱的列必须显式 `precision = 10, scale = 4`。
3. **MySQL 的 `bit(1)` 列在批量导出时是原始控制字节**（0x00/0x01）：PG 的 `COPY` 拒收 0x00，
   连 `mysql --xml` 的输出都会因此**不是合法 XML**。导出 SQL 里必须 `cast(col as unsigned)`。
4. **空字符串 ≠ NULL**：`--xml` 里只有 `xsi:nil` 是 NULL，空元素是空串。第一版把两者混为一谈，**丢了 19 条文本**。
5. **`mysql` 客户端没有 `--null` 选项**（`unknown variable 'null=\N'`）→ v1 的 TSV 方案不可行；
   最终用 `--xml` + 自己按 PG `COPY FORMAT text` 规则转义。
6. **服务器只有 python 3.6**：`subprocess.run` **不能同时给 `input=` 和 `stdin=`**（3.7 才允许）。
7. **compose 只透传 `environment:` 里列出的变量（坑 36）**：`DB_URL` 等四个键没加进去时，
   "切换"看起来成功、其实应用还在 MySQL 上。**判断依据**：容器 env + `pg_stat_activity` + MySQL 连接数三处一起看。
8. **Spring 的 `${VAR:默认值}` 只在"变量缺失"时用默认，空字符串会覆盖默认**：所以 compose 里 DB_* 的默认值
   写成 `${DB_USER:-${MYSQL_APP_USER:-root}}`（嵌套插值实测可用），避免"设了空值把库连坏"。
9. **`QUARTZ_DELEGATE` 必须是全限定类名** `org.quartz.impl.jdbcjobstore.PostgreSQLDelegate`（2026-09-18 实测）。
   配成简写（Quartz 有"补默认包名"的兜底，能不能生效看版本，别赌）或漏配 → 退到 `StdJDBCDelegate`
   → 用 `ResultSet.getBlob` 读 PG 的 bytea 列 → 报 **`不良的类型值 long : \x`**，
   表现是**每次重启都刷"恢复提醒调度失败 reminderId=…"**（提醒恢复属于"用户长期记忆不丢失"那条线，不能有 ERROR）。
   **判断依据**：本地同一份数据、只把 `QUARTZ_DELEGATE` 补成全限定名，重启后 ERROR 归零。

## 回滚（MySQL 容器与数据卷都还在）

```
cd /opt/wechat-agent-infra
sed -i '/^DB_URL=/d;/^DB_DRIVER=/d;/^DB_USER=/d;/^DB_PASSWORD=/d;/^QUARTZ_DELEGATE=/d' .env
AGENT_IMAGE=$(docker inspect wechat-agent-java -f '{{.Config.Image}}') \
  docker compose -f docker-compose.remote.yml up -d --no-build agent
```
**注意**：切换之后写的数据只在 pg 里，回滚会丢这部分——所以回滚要在发现问题时马上做。

## 相关的其它改动

- 数据源参数化：`DB_URL/DB_DRIVER/DB_USER/DB_PASSWORD`（默认值仍是 MySQL，不设即行为不变）
- 每晚**库级备份**（`DatabaseDumpService`）：按数据源自动选 `pg_dump`/`mysqldump`，落 `backup/<yyyyMMdd>.sql.gz`
- Quartz：`QUARTZ_DELEGATE` 可配（pg 用**全限定名** `org.quartz.impl.jdbcjobstore.PostgreSQLDelegate`，见坑 9）；QRTZ 表在 pg 里已建好（切库不用重建）