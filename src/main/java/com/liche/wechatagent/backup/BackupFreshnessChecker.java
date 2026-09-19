package com.liche.wechatagent.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;

/**
 * 备份新鲜度检查（2026-09-20 加）。
 *
 * <p><b>为什么需要</b>：备份是每天 03:00 由应用自己跑的（{@link MemoryBackupJob}），跑失败只写一行 ERROR
 * 进日志。如果那一刻容器正好在重启或重建（这个项目部署很频繁），那天就<b>一份备份都没有</b>，
 * 而且<b>不会有人被告知</b>——「用户长期记忆不丢失」这条优先级因此缺一道防线。所以这里只做一件事：
 * 判断最近一份备份是不是太旧了；由 {@code AlertNotifier} 拿去推 QQ 告警，面板总览也会因此标成 DEGRADED。
 *
 * <p>判定口径：`backup/` 下形如 `&lt;yyyyMMdd&gt;.zip`（逻辑备份）与 `&lt;yyyyMMdd&gt;.sql.gz`（库级备份）
 * 的文件里，**最新的那份**的修改时间距今超过 {@code backup.max-age-hours}（默认 26 小时，
 * 比每天一次的周期多留 2 小时给慢跑和重启）就算不新鲜。备份目录不存在、或者一个备份包都没有，
 * 同样算不新鲜——生产上这两种都意味着「真出事时取不回数据」。
 *
 * <p>检查自身出任何异常都当作正常（告警系统不能把主流程带崩，也不能因为读不了目录就误报）。
 */
@Component
public class BackupFreshnessChecker {

    private static final Logger log = LoggerFactory.getLogger(BackupFreshnessChecker.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    /** 只认这两种文件名，避免把 `.backup-xxxx.zip.tmp` 这类临时文件当成备份 */
    private static final String BACKUP_NAME = "\\d{8}\\.(zip|sql\\.gz)";

    private final Path backupDir;
    private final int maxAgeHours;
    private final ZoneId zone;

    public BackupFreshnessChecker(@Value("${backup.dir:backup}") String backupDir,
                                 @Value("${backup.max-age-hours:26}") int maxAgeHours,
                                 @Value("${app.time-zone:Asia/Shanghai}") String timeZone) {
        this.backupDir = Path.of(backupDir).toAbsolutePath().normalize();
        this.maxAgeHours = Math.max(0, maxAgeHours);
        this.zone = parseZone(timeZone);
    }

    /**
     * @param fresh      是否新鲜（= 没问题）
     * @param ageHours   最新备份距今多少小时；没有任何备份时为 -1
     * @param newestFile 最新备份的文件名；没有时为 null
     * @param message    不新鲜时给人看的说明（新鲜时为空串）
     */
    public record Status(boolean fresh, long ageHours, String newestFile, String message) {
    }

    /** {@code backup.max-age-hours=0} 表示不检查这一条 */
    public boolean enabled() {
        return maxAgeHours > 0;
    }

    public Status check() {
        if (!enabled()) {
            return new Status(true, 0, null, "");
        }
        try {
            if (!Files.isDirectory(backupDir)) {
                return new Status(false, -1, null, "备份目录不存在：" + backupDir);
            }
            Path newest = null;
            long newestAt = Long.MIN_VALUE;
            try (Stream<Path> entries = Files.list(backupDir)) {
                for (Path path : entries.toList()) {
                    if (!Files.isRegularFile(path)) {
                        continue;
                    }
                    if (!path.getFileName().toString().matches(BACKUP_NAME)) {
                        continue;
                    }
                    long modified = Files.getLastModifiedTime(path).toMillis();
                    if (modified > newestAt) {
                        newestAt = modified;
                        newest = path;
                    }
                }
            }
            if (newest == null) {
                return new Status(false, -1, null, "备份目录里没有任何备份包：" + backupDir);
            }
            long ageHours = Math.max(0, (System.currentTimeMillis() - newestAt) / 3_600_000L);
            if (ageHours >= maxAgeHours) {
                String when = LocalDateTime.ofInstant(Instant.ofEpochMilli(newestAt), zone).format(TIME);
                return new Status(false, ageHours, newest.getFileName().toString(),
                        String.format("备份已 %d 小时没更新（最近 %s，%s；阈值 %d 小时），去看看 03:00 那次备份为什么没跑成",
                                ageHours, newest.getFileName(), when, maxAgeHours));
            }
            return new Status(true, ageHours, newest.getFileName().toString(), "");
        } catch (IOException | RuntimeException exception) {
            log.debug("备份新鲜度检查跳过：{}", exception.toString());
            return new Status(true, 0, null, "");
        }
    }

    private static ZoneId parseZone(String value) {
        try {
            return ZoneId.of(value);
        } catch (RuntimeException ignored) {
            return ZoneId.of("Asia/Shanghai");
        }
    }
}
