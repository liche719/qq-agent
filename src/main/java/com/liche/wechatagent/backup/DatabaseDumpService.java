package com.liche.wechatagent.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * 库级备份：每天一份 {@code backup/<yyyyMMdd>.sql.gz}（2026-09-17 加）。
 *
 * <p>为什么要有它：{@link MemoryBackupJob} 那份是**逻辑备份**（每个用户的 state.json），
 * 它按业务模型导出，**导不出"表结构 + 全部列 + 那些没进模型的字段"**。真出事要恢复时，
 * 一份 `pg_dump`/`mysqldump` 才是最后一道防线（坑 18：数据卷 ≠ 备份）。
 *
 * <p>按**数据源 URL 自动选工具**：`jdbc:postgresql://` → {@code pg_dump}；`jdbc:mysql://` → {@code mysqldump}。
 * 所以迁 pg 之后这里一行不用改（这正是"换库只改 env"的一部分）。
 *
 * <p>纪律：**失败不打断用户级备份**——只记 WARN 并返回 null；镜像里没有对应客户端时也走同一条路。
 */
@Component
public class DatabaseDumpService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseDumpService.class);
    private static final Pattern URL = Pattern.compile(
            "^jdbc:(postgresql|mysql)://([^:/?]+)(?::(\\d+))?/([^?]+)");

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String backupDir;
    private final boolean enabled;

    public DatabaseDumpService(@Value("${spring.datasource.url:}") String jdbcUrl,
                               @Value("${spring.datasource.username:}") String username,
                               @Value("${spring.datasource.password:}") String password,
                               @Value("${backup.dir:backup}") String backupDir,
                               @Value("${backup.dump-enabled:true}") boolean enabled) {
        this.jdbcUrl = jdbcUrl == null ? "" : jdbcUrl;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.backupDir = backupDir == null || backupDir.isBlank() ? "backup" : backupDir;
        this.enabled = enabled;
    }

    /** 导出当天的一份库级备份；成功返回文件路径，跳过/失败返回 null */
    public Path dump(LocalDate day) {
        if (!enabled) {
            log.info("库级备份已关闭（backup.dump-enabled=false）");
            return null;
        }
        Parsed parsed = parse(jdbcUrl);
        if (parsed == null) {
            log.warn("认不出数据源 URL，跳过库级备份：{}", jdbcUrl);
            return null;
        }
        Path dir = Path.of(backupDir);
        Path target = dir.resolve(day.format(DateTimeFormatter.BASIC_ISO_DATE) + ".sql.gz");
        Path temporary = null;
        try {
            Files.createDirectories(dir);
            temporary = Files.createTempFile(dir, ".dump-", ".sql.gz.tmp");
            List<String> command = parsed.postgres() ? pgDumpCommand(parsed) : mySqlDumpCommand(parsed);
            ProcessBuilder builder = new ProcessBuilder(command);
            if (parsed.postgres()) {
                builder.environment().put("PGPASSWORD", password);
            } else {
                builder.environment().put("MYSQL_PWD", password);
            }
            Process process = builder.start();
            try (InputStream source = process.getInputStream();
                 OutputStream sink = new GZIPOutputStream(Files.newOutputStream(temporary))) {
                source.transferTo(sink);
            }
            String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int code = process.waitFor();
            if (code != 0) {
                log.warn("库级备份失败（{} 退出码 {}）：{}", command.get(0), code, shorten(error));
                Files.deleteIfExists(temporary);
                return null;
            }
            long size = Files.size(temporary);
            if (size <= 0) {
                log.warn("库级备份产出为空（{}），丢弃", command.get(0));
                Files.deleteIfExists(temporary);
                return null;
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("库级备份完成: {} ({} KB, {})", target, size / 1024, command.get(0));
            return target;
        } catch (IOException exception) {
            log.warn("库级备份没做成（{}）：{}", jdbcUrl, exception.getMessage());
            safeDelete(temporary);
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            safeDelete(temporary);
            return null;
        }
    }

    private void safeDelete(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败无所谓
        }
    }

    private List<String> pgDumpCommand(Parsed parsed) {
        List<String> command = new ArrayList<>(List.of("pg_dump", "--no-owner", "--no-privileges",
                "-h", parsed.host(), "-p", String.valueOf(parsed.port())));
        if (!username.isBlank()) {
            command.add("-U");
            command.add(username);
        }
        command.add("-d");
        command.add(parsed.database());
        return command;
    }

    private List<String> mySqlDumpCommand(Parsed parsed) {
        List<String> command = new ArrayList<>(List.of("mysqldump", "--single-transaction", "--quick",
                "--routines", "--events", "-h", parsed.host(), "-P", String.valueOf(parsed.port())));
        if (!username.isBlank()) {
            command.add("-u");
            command.add(username);
        }
        command.add(parsed.database());
        return command;
    }

    private String shorten(String text) {
        if (text == null || text.isBlank()) {
            return "(无输出)";
        }
        String single = text.replace('\n', ' ');
        return single.length() <= 300 ? single : single.substring(0, 299) + "…";
    }

    /** 从 JDBC URL 里取 host/port/db/方言；认不出来返回 null */
    static Parsed parse(String url) {
        if (url == null) {
            return null;
        }
        Matcher matcher = URL.matcher(url.trim());
        if (!matcher.find()) {
            return null;
        }
        boolean postgres = "postgresql".equalsIgnoreCase(matcher.group(1));
        String host = matcher.group(2);
        int port = matcher.group(3) == null ? (postgres ? 5432 : 3306) : Integer.parseInt(matcher.group(3));
        return new Parsed(postgres, host, port, matcher.group(4));
    }

    record Parsed(boolean postgres, String host, int port, String database) {
    }
}
