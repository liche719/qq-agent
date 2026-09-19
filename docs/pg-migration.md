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

## 回滚（MySQL 容器已退役，2026-09-20 起卷也删了）

**2026-09-20 按用户决定收尾**：确认 pg 里数据齐全后，把最后一份 MySQL 整库 dump 导出来、
然后删掉了数据卷 `wechat-agent-infra_mysql-data` 与 `mysql:8.0.46` 镜像（合计回收 ~1.0 GB）。
所以**下面这套"原样回滚"已经不可用**——卷没了，起 mysql 只会得到空库。

删之前的那份 dump（`--all-databases`，1.2 MB，41 张表；`user_profile=3`、`conversation_memory=1160`、
旧 `user_core_memory`/`user_work_memory`/`episodic_memory` = 29/64/18，与当年迁进 pg 的数字吻合）：

- 服务器 `/root/wechat-agent-mysql-final-dump-20260920.sql.gz`
- 本地 `C:\Users\33721\Desktop\wechat-agent\wechat-agent-mysql-final-dump-20260920.sql.gz`
  （sha256 `e4f062357d368b784aed96af3784b5794445996e3260423c6c6fe4c5090ce44a`）

真要再看旧库，用这份 dump 起一个临时 MySQL 灌进去即可（**不要**指望卷）：
`docker run -d -e MYSQL_ROOT_PASSWORD=x mysql:8.0.46` → `zcat dump.sql.gz | docker exec -i <容器> mysql -uroot -px`。

**为什么敢删**：① pg 侧逐表计数只多不少（迁移后还在长）；② 迁移前后各有 mysqldump 快照留在
`/root`（`wechat-agent-backup-20260912015146.sql.gz`、`-clockfix-…`、`-baseline-202609142104.sql.gz`、
以及 `backup/20260917.sql.gz`）；③ 全库 pg dump 每天在跑，另有一份自包含导出包。

**顺带封存了一个雷**：服务器 `/opt/wechat-agent-infra/docker-compose.yml` 是 09-11 从本地开发版拷过去的
残留（里面的 `mysql` 口令还是 `root`），4 个容器其实全来自 `docker-compose.remote.yml`。谁要是**不加 `-f`**
在那个目录跑一次 `docker compose up -d`，它会去建同名容器，把正在跑的 redis（丢 `--requirepass`）、
searxng（丢调好的 settings 挂载）一起换掉，还会凭空重建一个 mysql 卷。已改名为
`docker-compose.localdev-unused.txt`（内容没动），现在不加 `-f` 只会得到 `no configuration file provided`。

（以下为历史回滚步骤，卷删除后已失效，留作参考）

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