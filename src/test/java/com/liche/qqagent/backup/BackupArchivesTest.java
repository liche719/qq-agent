package com.liche.qqagent.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** 备份打包与历史查找（2026-10-27 从 MemoryBackupJob 抽出）：以前只能等凌晨跑完才知道对不对。 */
class BackupArchivesTest {

    @Test
    void zipsDayDirectoryWithRelativeEntries(@TempDir Path backupDir) throws Exception {
        Path dayDir = backupDir.resolve("20261028");
        Files.createDirectories(dayDir.resolve("user-a"));
        Files.writeString(dayDir.resolve("user-a/state.json"), "{}");
        Files.writeString(dayDir.resolve("index.txt"), "hello");
        Path archive = backupDir.resolve("20261028.zip");

        BackupArchives.zipDay(backupDir, dayDir, archive);

        assertTrue(Files.exists(archive));
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            List<String> names = zip.stream().map(e -> e.getName()).sorted().toList();
            assertEquals(List.of("index.txt", "user-a/state.json"), names);
        }
        // 临时文件不能留在目录里冒充备份
        try (var listing = Files.list(backupDir)) {
            assertTrue(listing.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void missingDayDirectoryIsNoOp(@TempDir Path backupDir) throws Exception {
        Path archive = backupDir.resolve("20261028.zip");
        BackupArchives.zipDay(backupDir, backupDir.resolve("20261028"), archive);
        assertFalse(Files.exists(archive), "没有目录就不该产出空包");
    }

    @Test
    void listsOnlyDayArchivesInOrder(@TempDir Path backupDir) throws Exception {
        Files.writeString(backupDir.resolve("20261026.zip"), "x");
        Files.writeString(backupDir.resolve("20261028.zip"), "x");
        Files.writeString(backupDir.resolve("notes.txt"), "x");

        List<Path> archives = BackupArchives.dayArchives(backupDir);

        assertNotNull(archives);
        assertEquals(List.of("20261026.zip", "20261028.zip"),
                archives.stream().map(p -> p.getFileName().toString()).toList());
    }

    @Test
    void unreadableDirectoryReturnsNull(@TempDir Path tempDir) {
        assertNull(BackupArchives.dayArchives(tempDir.resolve("nope")),
                "读不到目录要返回 null，调用方据此区分「没有备份」和「读不到」");
    }
}
