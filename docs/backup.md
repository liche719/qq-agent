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

- `state.json` 里一个用户一份，字段：`userId / profile / coreMemories / workMemories / archives /
  changeLogs / conversationMemories / episodicMemories / reminders / storedMedia / mediaArtifacts`。
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