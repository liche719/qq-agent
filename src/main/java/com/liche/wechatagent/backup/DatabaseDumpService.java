package com.liche.wechatagent.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
                transferDump(parsed.postgres(), source, sink);
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

    /**
     * 把 dump 从 pg_dump 的 stdout 搬到 gzip 文件里，顺手**丢掉服务端不认识的语句**。
     *
     * <p>为什么需要（2026-09-19 实测踩到）：镜像里的 `pg_dump` 版本**跟着基础镜像走**，可能比数据库新
     * （当时是客户端 18.6 / 服务端 16.14）。pg_dump 会把**自己版本**的默认设置写进 dump 头，于是 PG16
     * 恢复时第一行就 `ERROR: unrecognized configuration parameter "transaction_timeout"`；psql 18 还会写
     * `restrict` / `unrestrict` 这类 16 的 psql 不认的反斜杠元命令。**严格模式（`-v ON_ERROR_STOP=1`）直接失败**——
     * 备份"在"但恢复不了，等于没有。
     *
     * <p>处理办法不是硬编码某几个名字，而是**问服务端自己**：`select name from pg_settings` 拿它认识的参数集合，
     * 头部里 `SET` 了不在集合里的就丢掉。COPY 数据块内一律不动（只在"不在数据块里"时过滤）。
     */
    private void transferDump(boolean postgres, InputStream source, OutputStream sink) throws IOException {
        if (!postgres) {
            // mysqldump 没有这个问题
            source.transferTo(sink);
            return;
        }
        Set<String> known = knownSettings();
        BufferedReader reader = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8));
        boolean inCopyData = false;
        int droppedSettings = 0;
        int droppedMeta = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            if (inCopyData) {
                if ("\\.".equals(line.trim())) {
                    inCopyData = false;
                }
            } else if (line.startsWith("COPY ") && line.trim().endsWith("FROM stdin;")) {
                inCopyData = true;
            } else if (line.startsWith("\\restrict") || line.startsWith("\\unrestrict")) {
                droppedMeta++;
                continue;
            } else if (known != null && unknownSetting(line, known)) {
                droppedSettings++;
                continue;
            }
            writer.write(line);
            writer.write('\n');
        }
        writer.flush();
        if (droppedSettings > 0 || droppedMeta > 0) {
            log.info("库级备份：丢弃 {} 条服务端不认识的 SET + {} 条 psql 元命令（客户端比服务端新时的兼容处理）",
                    droppedSettings, droppedMeta);
        }
    }

    private boolean unknownSetting(String line, Set<String> known) {
        if (!line.startsWith("SET ")) {
            return false;
        }
        int equals = line.indexOf(" = ");
        if (equals <= 4) {
            return false;
        }
        return !known.contains(line.substring(4, equals).trim());
    }

    /** 服务端认识的参数名；查不到就返回 null（表示不过滤，宁可留下也不误删） */
    private Set<String> knownSettings() {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select name from pg_settings")) {
            Set<String> names = new HashSet<>();
            while (rows.next()) {
                names.add(rows.getString(1));
            }
            return names;
        } catch (Exception exception) {
            log.warn("查 pg_settings 失败（本次不做 SET 过滤）：{}", exception.getMessage());
            return null;
        }
    }

    private void safeDelete(Path path) {        if (path == null) {
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
