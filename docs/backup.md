# 每日备份（`backup/MemoryBackupJob.java`）

> 2026-09-13 改版并端到端验证。起因：用户觉得备份占空间——一查**当时只有 568K**，
> 但设计的增长方式确实有问题（见下），于是改成「每天一个 zip + 媒体共享一份」。

## 一句话

每天 03:00 把**每个用户**的记忆/提醒/资料元数据写成一个明文 JSON，打成
`backup/<yyyyMMdd>.zip`；媒体**本体**按 sha256 存在共享的 `backup/media/<sha256>.bin`，
同一份内容只存一次。

## 磁盘上的样子

```
backup/
├─ 20260913.zip                 # 当天备份：里面是 user-<hash>/state.json（明文 JSON）
├─ 20260914.zip
└─ media/
   └─ <sha256>.bin              # 媒体本体，跨天共享；名字就是内容的哈希
```

- `state.json` 里一个用户一份，字段：`userId / profile / memories / changeLogs / conversationMemories /
  conversationMemoryBackupLimit / reminders / storedMedia / mediaArtifacts`。
  （**2026-09-18 记忆三表合并**后记忆只剩一个 `memories` 数组；`coreMemories` / `workMemories` /
  `episodicMemories` / `archives` 都是老备份包的字段，清理逻辑仍认前三个。）
- `mediaArtifacts[]` 记录每份媒体的 `status`（`OK / MISSING_SOURCE / MISSING_HASH / HASH_MISMATCH /
  COPY_FAILED`）、`path`（`media/<sha256>.bin`）、`sizeBytes`，以及复用时才有的 `reused: true`。
- **为什么是 zip 不是 tar.gz**：Java 标准库没有 tar，用 `java.util.zip` 零依赖；
  解压出来就是明文 `state.json`，照样能直接看（所以没再额外 gzip 一层）。

## 为什么改

原来是 `backup/<yyyyMMdd>/user-<hash>/{state.json, media/<id>-<sha>.bin}`：

| 问题 | 后果 |
|---|---|
| 媒体本体**每天复制一份**、保留 30 天（`backup.retention-days`） | 存过 100MB 的图 → 备份 3GB。压缩也救不了（JPEG/PDF 本来就压过了） |
| `state.json` 是 pretty-print 明文，不压缩 | 一个人 508K/天，30 天 ≈ 15M |
| 每天一个目录树 | 文件数 ×30，清理和列目录都变慢 |

改成现在这样之后：**占用 ≈ 媒体总量 ×1（而不是 ×30） + JSON/10 ×30**。

## 关键行为

- **复用**：写媒体前先看 `backup/media/<sha>.bin` 是否存在且大小一致，是就不复制，只标 `reused: true`
  （每天备份不再重复读一遍媒体内容）。
- **打包**：当天所有用户写完 → 打 zip → **删掉当天目录**；zip 写失败就不删目录（保住数据）。
- **保留 30 天**：`prune()` 同时认新的 `<yyyyMMdd>.zip` 和**老版本的 `<yyyyMMdd>/` 目录**（各自按日期删）。
- **媒体回收**：只有真的删掉了过期备份时才做一次 GC——把不再被任何剩余压缩包引用的 blob 删掉。
  代价是孤儿 blob 最多多留 `retention-days` 天（有界），换来每天不必扫 30 个包。
- **遗忘清理**：`/memory forget` 会遍历所有 `<yyyyMMdd>.zip`，把 `user-<hash>/state.json` 取到临时文件、
  用原来的改写逻辑改完、**重写整个压缩包**（其余条目原样搬运）。`complete=false` 时调用方会记警告。
  ⚠️ **老版本留下的 `<yyyyMMdd>/` 目录不再被遗忘清理覆盖**——不过它会在下一次成功备份时被同名目录
  "吃掉"（当天目录名相同）并打包进 zip，之后磁盘上就只剩 `.zip` 了。

## 恢复怎么做

```bash
cd /opt/wechat-agent-infra
unzip -o backup/20260913.zip -d /tmp/restore-20260913      # 解出 user-*/state.json
# 媒体本体在 backup/media/ 下，用 state.json 里 mediaArtifacts[].path 对回去
```

## 验证记录（2026-09-13，全部实测）

- **打包**：跑完一次备份后 `backup/<今天>.zip` 出现、当天目录被删、包里是 4 个 `user-*/state.json`、
  里面**没有** `.bin` 残留。
- **去重**：同一份假媒体（5600 字节）连跑两次备份 → 共享目录里始终只有一份、blob 的 mtime **没变**、
  第二次的 artifact 标了 `reused: true`（第一次是真复制）。
- **遗忘清理**：往库里插一条含关键词的工作记忆 → 等它进包 → 发 `/memory forget <关键词>` →
  包被重写（mtime 变了）、那条记忆在包里**消失**、包仍是完整 JSON、其他用户条目与你自己的备份都还能解析。
- **回收**：造一个过期备份 `20250101.zip` + 一个没人引用的 blob → 下次备份的 `prune` 把过期备份删掉，
  并只回收那个没人引用的 blob，**仍被引用的那份留着**。
- **体积**：568K → **152K**。数据核对：`exam_plan` 1、`exam_task` 3、`user_profile` 3，测试用户行清零。
- 测试用的假媒体/假记忆/测试用户**全部已清**；`BACKUP_CRON` 已还原 03:00、模拟器通道已还原 `disabled`。

## 一个必须知道的历史事实

服务器 `stored_media` 表里有 **22 条媒体记录（2026-08-31 ~ 09-02）**，但磁盘上 `stored-media/`
**一个文件都没有**——那些是**本地跑**的时候存的：**行**跟着数据库搬到了服务器，**文件**没搬
（代码里没有任何同步逻辑，CI 也只传镜像和 compose）。而服务器在 09-13 00:11 之前连 `stored-media`
都没挂出来（见 `AGENTS.md` 坑 38），所以即便当时在服务器存过也会随容器重建丢失。
现在这些文件两边都没有了，只剩元数据。**这也说明「备份里留一份媒体本体」是有价值的**，
只是它当时没赶上——现在改成了共享一份，成本可控。

---

## 库级备份（2026-09-17 加）

除了上面那份**逻辑备份**（`backup/<yyyyMMdd>.zip`，每个用户的 `state.json`），每天还会多一份
**库级备份** `backup/<yyyyMMdd>.sql.gz`：

- 由 `DatabaseDumpService` 执行，**按数据源 URL 自动选工具**：`jdbc:postgresql://` → `pg_dump`，
  `jdbc:mysql://` → `mysqldump`（所以迁 pg 之后这里不用改）
- 镜像里两个客户端都装了（Dockerfile 的 `postgresql-client` + `default-mysql-client`）
- 开关 `BACKUP_DUMP_ENABLED`（默认 true）；保留天数与逻辑备份共用 `BACKUP_RETENTION_DAYS`
- 为什么会需要它：逻辑备份按业务模型导出，**导不出表结构和没进模型的列**；整库恢复只能靠这份

**恢复（pg）**：`gunzip -c backup/<day>.sql.gz | psql -U <user> -d wechat_agent`
**恢复（mysql）**：`gunzip -c backup/<day>.sql.gz | mysql -u <user> -p wechat_agent`

**验证方法**（别等到凌晨三点）：把 `BACKUP_CRON` 临时改成 `0 */2 * * * ?`，重建容器，
确认宿主机 `backup/` 下真的出现当天的 `.zip` 与 `.sql.gz`（日志里会有「库级备份完成」），
验完改回 `0 0 3 * * ?` —— 这就是坑 38/46 记下来的做法。

---

## 客户端/服务端版本错配（2026-09-20 修）

Dockerfile 的 `postgresql-client` **没钉版本**，基础镜像换到 Ubuntu 26.04 后装的是 **pg_dump 18.6**，
而服务端是 **PostgreSQL 16.14** → dump 里带 `SET transaction_timeout = 0;` 和 `\restrict` 元命令，
`psql -v ON_ERROR_STOP=1` 恢复直接 `ERROR: unrecognized configuration parameter "transaction_timeout"`。

- 修法：`DatabaseDumpService.transferDump()` 在**非 COPY 数据块内**丢掉服务端不认识的 `SET <guc>` 行
  （不认识哪些由 `select name from pg_settings` 现场决定）和 `\restrict`/`\unrestrict` 元命令。
  **只在 COPY 块外过滤**：数据体里出现同形文本不能动。
- 反向也一样：用 16 的客户端打、往 18 恢复没问题，但反过来必然踩。**要长期可靠就把客户端版本钉到服务端大版本。**

## 把数据全部拿走（2026-09-20，服务器到期场景）

「有备份」不等于「能把数据拿出来」——卷和备份都在同一台机器上。所以除日常两份备份外，
另有一个**自包含导出包**（`/root/wechat-agent-full-export-20260920.tar.gz`，8.8 MB、
sha256 `afd53e0b…`，本地另存一份 `C:\Users\33721\Desktop\wechat-agent\`），内容：

| 成员 | 说明 |
|---|---|
| `db/<day>.sql.gz` | 全库 dump（39 张表） |
| `snapshots/<day>.zip` | 每用户 `state.json` 逻辑快照（不依赖数据库也能读） |
| `media/stored-media/`、`media/backup-media/` | 原始媒体 + 去重后的共享副本 |
| `legacy/*.sql` | 三表合并前的两代老备份（`V16` 之前），保险用 |
| `counts.tsv` | 打包那一刻**每张表的行数**，恢复后照它对 |
| `SHA256SUMS` | 包内每个文件的 sha256（`sha256sum -c`） |
| `README-restore.md` | 恢复步骤 |

**闭环验证（已做，不是推测）**：把包里的 `db/<day>.sql.gz` 灌进一个干净库（本地 `wa-pg-rehearsal`），
`ON_ERROR_STOP=1` 零报错、**39 张表逐表行数与 `counts.tsv` 零差异**，抽样 `memory` 109（PROFILE 29 /
TASK 65 / EXPERIENCE 15）、`conversation_memory` 1332 行 4 个用户、`memory_fact` 10、`exam_plan` 1，
`memory` 里 `sim-%` 行数 0；媒体 9 个原始文件 + 7 个共享 blob + 3 份 legacy sql 都在包里。
**换机器恢复只要 PostgreSQL 16 + pgvector**；服务器 `.env`（口令）只有「想把服务原样跑起来」时才需要，
只要数据不需要它。

**包里那份 dump 的来历**：刻意用**服务端自己**的 `pg_dump`（postgres 容器里的 16.14）现打，
参数与 `DatabaseDumpService` 一致（`--no-owner --no-privileges`），所以不会带客户端比服务端新产生的
`SET transaction_timeout` 那类兼容问题。日常 03:00 那份仍由镜像里的客户端打（已加 GUC 过滤兜住）。

**两个口径**：① 逻辑备份的 `state.json` 按 `user_profile` 遍历，所以**没建档的账号不在这份里**
（库里那 4 条 `sim-iv-check`/`sim-iv-check2` 的测试经历就是这种），**全库 dump 才是无遗漏的**；
② 机主 `memories=109` 与库里 113 的差额就是那 4 条，不是备份丢数据。

那 4 条测试账号的残留已在 2026-09-20 清掉（连 7 条变更日志一起，事务内删除；undo 在服务器
`/root/wechat-agent-memory-cleanup-undo-20260920.sql`，1.5 MB，服务端 16.14 的 `pg_dump --data-only`
打的、可直接回灌），清完 `memory` = **109（PROFILE 29 / TASK 65 / EXPERIENCE 15）**，与备份完全一致。
`memory_change_log` 里还剩 12 条、`operation_log` 里 7 条属于更早的 `sim-*` 测试账号——那些是审计/变更
日志、不是记忆，且面板按机主 user_id 过滤看不到，留着没影响。

---

## 备份新鲜度告警（2026-09-20 加）

**为什么**：备份每天 03:00 由应用**自己**跑（`MemoryBackupJob`），跑失败只写一行 ERROR 进日志。
这个项目部署很频繁，03:00 那一刻如果容器正在重建，那天就**一份备份都没有**——而它是「用户长期记忆
不丢失」的最后一道防线，却没有任何人会被告知。所以补一条主动判定：

- `BackupFreshnessChecker`：看 `backup/` 下形如 `<yyyyMMdd>.zip`（逻辑备份）与 `<yyyyMMdd>.sql.gz`
  （库级备份）的文件里**最新那份**的 mtime，距今超过 `backup.max-age-hours`（`BACKUP_MAX_AGE_HOURS`，
  默认 **26** 小时；`0` = 不检查）就算不新鲜。目录不存在、或一个备份包都没有，同样算不新鲜。
  只认这两种文件名，`.backup-xxx.zip.tmp` 这类临时文件不会被当成备份。检查自身出错一律当正常（不误报）。
- 两个出口：`AlertNotifier` 推 QQ 告警（问题出现与恢复各一条，走既有的 `repeat-minutes` 去重）；
  `AdminDashboardController.overview()` 把同一条塞进 `alerts` → 面板 `status` 变 **DEGRADED**（前端早就会渲染）。
- **实测（2026-09-20 01:56）**：把 `backup/` 下所有备份包的 mtime 改成 3 天前 → 日志出现
  「已推送运维告警：备份已 72 小时没更新（最近 …，阈值 26 小时）」，面板 `status=DEGRADED` 且 `alerts`
  带同一条；把 mtime 原样还原 → 75 秒后推「运维恢复」，面板回 `UP`、`alerts` 空。
- **局限（要说清）**：这条检查跑在被监控的同一个进程里。**应用整个挂掉时它不会响**——那种情况要靠外部
  探活。它能抓的是「应用活着、但备份那天没跑成」，也就是最容易被忽略的那种。

**保留期**：`.env` 已显式写 `BACKUP_RETENTION_DAYS=30`（此前是 compose 的隐式默认值，值一样）。
注意 `docker compose up -d agent` 那次**没有重建容器**——compose 比的是**渲染后**的服务配置，
显式写 30 与默认 30 渲染结果相同，所以不触发重建；容器里 `BACKUP_RETENTION_DAYS=30` 是准的。