package com.liche.qqagent.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 备份包的打包与历史查找（2026-10-27 从 {@code MemoryBackupJob} 拆出，第 10 步）。
 *
 * <p>只做文件操作：把当天的目录压成 {@code yyyyMMdd.zip}、列出历史包。
 * 这样做有两个好处：
 *
 * <ul>
 *   <li><b>可测</b>：以前"打包对不对"只能等凌晨 03:00 跑完看结果；现在能用临时目录直接验；</li>
 *   <li><b>可读</b>：`MemoryBackupJob` 里剩下的都是"备份什么 / 什么时候备份 / 怎么清理"的业务判断。</li>
 * </ul>
 *
 * <p>打包用"临时文件 + 原子替换"：中途失败不会留下半个 zip 冒充成功备份
 * （历史上最怕的就是"看起来有包、其实是空的"）。
 */
final class BackupArchives {

    private BackupArchives() {
    }

    /** 把某天的目录压成 zip；不是目录就什么都不做 */
    static void zipDay(Path backupDir, Path dayDir, Path archive) throws IOException {
        if (!Files.isDirectory(dayDir)) {
            return;
        }
        Files.createDirectories(archive.getParent());
        Path temporary = Files.createTempFile(backupDir, ".backup-", ".zip.tmp");
        try {
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temporary));
                 Stream<Path> walk = Files.walk(dayDir)) {
                for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                    String name = dayDir.relativize(file).toString().replace('\\', '/');
                    out.putNextEntry(new ZipEntry(name));
                    Files.copy(file, out);
                    out.closeEntry();
                }
            }
            Files.move(temporary, archive, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * 历史备份包（按文件名升序，即按日期）。
     *
     * @return 列不出来时返回 null —— 调用方据此区分"没有备份"与"读不到备份目录"
     */
    static List<Path> dayArchives(Path backupDir) {
        try (Stream<Path> paths = Files.list(backupDir)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("\\d{8}\\.zip"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException exception) {
            return null;
        }
    }

    /** 备份包的文件名（不含目录），用于日志 */
    static String name(Path archive) {
        return archive == null ? "" : archive.getFileName().toString();
    }
}
