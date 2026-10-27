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

/** 追加用例：改写 zip 里的单个条目（"忘掉记忆"要就地改历史备份）。 */
class BackupRewriteTest {

    @org.junit.jupiter.api.Test
    void replacesOnlyTheTargetEntry(@org.junit.jupiter.api.io.TempDir java.nio.file.Path backupDir)
            throws Exception {
        java.nio.file.Path dayDir = backupDir.resolve("20261028");
        java.nio.file.Files.createDirectories(dayDir.resolve("user-a"));
        java.nio.file.Files.writeString(dayDir.resolve("user-a/state.json"), "{\"old\":true}");
        java.nio.file.Files.writeString(dayDir.resolve("index.txt"), "keep-me");
        java.nio.file.Path archive = backupDir.resolve("20261028.zip");
        BackupArchives.zipDay(backupDir, dayDir, archive);

        java.nio.file.Path replacement = backupDir.resolve("new-state.json");
        java.nio.file.Files.writeString(replacement, "{\"new\":true}");
        BackupArchives.rewriteEntry(backupDir, archive, "user-a/state.json", replacement);

        try (var zip = new java.util.zip.ZipFile(archive.toFile())) {
            assertEquals("{\"new\":true}",
                    new String(zip.getInputStream(zip.getEntry("user-a/state.json")).readAllBytes()));
            assertEquals("keep-me",
                    new String(zip.getInputStream(zip.getEntry("index.txt")).readAllBytes()));
        }
    }

    @org.junit.jupiter.api.Test
    void leavesTemporaryFilesBehind(@org.junit.jupiter.api.io.TempDir java.nio.file.Path backupDir)
            throws Exception {
        java.nio.file.Path dayDir = backupDir.resolve("20261028");
        java.nio.file.Files.createDirectories(dayDir);
        java.nio.file.Files.writeString(dayDir.resolve("a.txt"), "a");
        java.nio.file.Path archive = backupDir.resolve("20261028.zip");
        BackupArchives.zipDay(backupDir, dayDir, archive);
        java.nio.file.Path replacement = backupDir.resolve("r.txt");
        java.nio.file.Files.writeString(replacement, "r");

        BackupArchives.rewriteEntry(backupDir, archive, "a.txt", replacement);

        try (var listing = java.nio.file.Files.list(backupDir)) {
            org.junit.jupiter.api.Assertions.assertTrue(
                    listing.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "改写后不能留下临时文件");
        }
    }
}
