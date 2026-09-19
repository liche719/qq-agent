package com.liche.wechatagent.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liche.wechatagent.media.StoredMediaRepository;
import com.liche.wechatagent.memory.ConversationMemory;
import com.liche.wechatagent.memory.ConversationMemoryRepository;
import com.liche.wechatagent.memory.Memory;
import com.liche.wechatagent.memory.MemoryProvenance;
import com.liche.wechatagent.memory.MemoryChangeLog;
import com.liche.wechatagent.memory.MemoryChangeLogRepository;
import com.liche.wechatagent.memory.MemoryRepository;
import com.liche.wechatagent.reminder.ReminderTaskRepository;
import com.liche.wechatagent.user.UserProfile;
import com.liche.wechatagent.user.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryBackupJobTest {

    /** 备份按 app.time-zone（默认 Asia/Shanghai）算日期，测试不能拿 JVM 默认时区算 */
    private static final ZoneId BACKUP_ZONE = ZoneId.of("Asia/Shanghai");

    @TempDir
    Path tempDirectory;

    @Test
    void usesHashedUserDirectoriesAndWritesARecoverableStateFile() throws Exception {
        String userId = "qq:../../private-user";
        UserProfile profile = new UserProfile(userId, "陪伴助手");
        UserProfileRepository profiles = mock(UserProfileRepository.class);
        MemoryRepository memories = mock(MemoryRepository.class);
        MemoryChangeLogRepository changeLogs = mock(MemoryChangeLogRepository.class);
        ReminderTaskRepository reminders = mock(ReminderTaskRepository.class);
        StoredMediaRepository media = mock(StoredMediaRepository.class);
        when(profiles.findAll()).thenReturn(List.of(profile));
        when(profiles.findById(userId)).thenReturn(Optional.of(profile));
        when(memories.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of());
        when(changeLogs.findByUserIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
        when(reminders.findByUserIdAndStatus(userId, "PENDING")).thenReturn(List.of());
        when(media.findByUserIdOrderByCreatedAtAsc(userId)).thenReturn(List.of());

        Path backupRoot = tempDirectory.resolve("backup");
        MemoryBackupJob job = new MemoryBackupJob(profiles, memories, changeLogs, reminders, media,
                new ObjectMapper().findAndRegisterModules(), backupRoot.toString(), 30,
                tempDirectory.resolve("stored-media").toString());

        job.backupAll();

        String entryName = "user-" + shortHash(userId) + "/state.json";
        // 用户目录用哈希命名，不能带出原始 userId（里面有路径穿越字符）
        assertTrue(entryName.matches("user-[0-9a-f]{12}/state\\.json"));
        assertFalse(entryName.contains("private-user"));
        String state = readStateFromArchive(dayArchive(backupRoot), entryName);
        assertTrue(state.contains(userId));
        assertTrue(state.contains("mediaArtifacts"));
        // 三表合一后写的是一个 memories 数组，按 kind 分组交给读的人
        assertTrue(state.contains("\"memories\""));
    }

    @Test
    void redactsForgottenMemoryFromExistingBackupSnapshots() throws Exception {
        String userId = "qq:privacy-user";
        UserProfile profile = new UserProfile(userId, "陪伴助手");
        Memory memory = new Memory(userId, Memory.KIND_PROFILE, "这是必须彻底遗忘的私密目标");
        memory.setId(7L);
        MemoryChangeLog change = new MemoryChangeLog(userId, "ADD", Memory.KIND_PROFILE, 7L,
                null, "这是必须彻底遗忘的私密目标", "自动提取", "AUTO");
        UserProfileRepository profiles = mock(UserProfileRepository.class);
        MemoryRepository memories = mock(MemoryRepository.class);
        MemoryChangeLogRepository changeLogs = mock(MemoryChangeLogRepository.class);
        ReminderTaskRepository reminders = mock(ReminderTaskRepository.class);
        StoredMediaRepository media = mock(StoredMediaRepository.class);
        when(profiles.findAll()).thenReturn(List.of(profile));
        when(profiles.findById(userId)).thenReturn(Optional.of(profile));
        when(memories.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(memory));
        when(changeLogs.findByUserIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of(change));
        when(reminders.findByUserIdAndStatus(userId, "PENDING")).thenReturn(List.of());
        when(media.findByUserIdOrderByCreatedAtAsc(userId)).thenReturn(List.of());
        Path backupRoot = tempDirectory.resolve("privacy-backup");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        MemoryBackupJob job = new MemoryBackupJob(profiles, memories, changeLogs, reminders, media,
                mapper, backupRoot.toString(), 30, tempDirectory.resolve("stored-media").toString());

        job.backupAll();
        MemoryBackupJob.PurgeResult result = job.purgeForgottenMemory(userId, Memory.KIND_PROFILE, 7L);

        assertTrue(result.complete());
        assertEquals(1, result.filesUpdated());
        String state = readStateFromArchive(dayArchive(backupRoot), "user-" + shortHash(userId) + "/state.json");
        assertFalse(state.contains("这是必须彻底遗忘的私密目标"));
        var root = mapper.readTree(state);
        assertEquals(0, root.path("memories").size());
        assertTrue(root.path("changeLogs").get(0).path("afterContent").isNull());
    }

    @Test
    void backsUpAndPurgesConversationEvidenceWithinTheOwningUserSnapshot() throws Exception {
        String userId = "qq:memory-owner";
        String otherUserId = "qq:other-user";
        UserProfile owner = new UserProfile(userId, "陪伴助手");
        UserProfile other = new UserProfile(otherUserId, "陪伴助手");
        ConversationMemory ownedEvidence = new ConversationMemory(userId, "user", "event-owner",
                "用户的私密考研计划", List.of("message-owner"), LocalDateTime.now(), null);
        ConversationMemory foreignEvidence = new ConversationMemory(otherUserId, "user", "event-other",
                "另一位用户的私密计划", List.of("message-other"), LocalDateTime.now(), null);
        UserProfileRepository profiles = mock(UserProfileRepository.class);
        MemoryRepository memories = mock(MemoryRepository.class);
        MemoryChangeLogRepository changeLogs = mock(MemoryChangeLogRepository.class);
        ReminderTaskRepository reminders = mock(ReminderTaskRepository.class);
        StoredMediaRepository media = mock(StoredMediaRepository.class);
        ConversationMemoryRepository conversations = mock(ConversationMemoryRepository.class);
        when(profiles.findAll()).thenReturn(List.of(owner, other));
        when(profiles.findById(userId)).thenReturn(Optional.of(owner));
        when(profiles.findById(otherUserId)).thenReturn(Optional.of(other));
        when(memories.findByUserIdOrderByUpdatedAtDesc(any())).thenReturn(List.of());
        when(changeLogs.findByUserIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
        when(reminders.findByUserIdAndStatus(any(), eq("PENDING"))).thenReturn(List.of());
        when(media.findByUserIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(conversations.findByUserIdOrderByCreatedAtDesc(eq(userId), any())).thenReturn(List.of(ownedEvidence));
        when(conversations.findByUserIdOrderByCreatedAtDesc(eq(otherUserId), any())).thenReturn(List.of(foreignEvidence));
        Path backupRoot = tempDirectory.resolve("conversation-backup");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        MemoryBackupJob job = job(profiles, memories, changeLogs, reminders, media, conversations, mapper,
                backupRoot, 1000);

        job.backupAll();
        MemoryBackupJob.PurgeResult result = job.purgeForgottenConversationEvidence(userId,
                List.of("message-owner"), "用户的私密考研计划");

        assertTrue(result.complete());
        assertEquals(1, result.filesUpdated());
        Path archive = dayArchive(backupRoot);
        String ownerState = readStateFromArchive(archive, "user-" + shortHash(userId) + "/state.json");
        String otherState = readStateFromArchive(archive, "user-" + shortHash(otherUserId) + "/state.json");
        assertEquals(0, mapper.readTree(ownerState).path("conversationMemories").size());
        var otherStateJson = mapper.readTree(otherState);
        assertEquals(1, otherStateJson.path("conversationMemories").size());
        assertTrue(otherStateJson.toString().contains("另一位用户的私密计划"));
    }

    @Test
    void purgesExperienceBackupByItsSourceMessage() throws Exception {
        String userId = "qq:episode-owner";
        UserProfile profile = new UserProfile(userId, "陪伴助手");
        UserProfileRepository profiles = mock(UserProfileRepository.class);
        MemoryRepository memories = mock(MemoryRepository.class);
        MemoryChangeLogRepository changeLogs = mock(MemoryChangeLogRepository.class);
        ReminderTaskRepository reminders = mock(ReminderTaskRepository.class);
        StoredMediaRepository media = mock(StoredMediaRepository.class);
        ConversationMemoryRepository conversations = mock(ConversationMemoryRepository.class);
        Memory episode = new Memory(userId, Memory.KIND_EXPERIENCE, "用户曾因申请表提醒异常而着急");
        episode.setId(3L);
        episode.setTitle("申请表事件");
        episode.setSourceType("USER_EXPLICIT");
        episode.setSourceMessageIds("episode-message");
        episode.setOccurredAt(LocalDateTime.now());
        when(profiles.findAll()).thenReturn(List.of(profile));
        when(profiles.findById(userId)).thenReturn(Optional.of(profile));
        when(memories.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(episode));
        when(changeLogs.findByUserIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
        when(reminders.findByUserIdAndStatus(userId, "PENDING")).thenReturn(List.of());
        when(media.findByUserIdOrderByCreatedAtAsc(userId)).thenReturn(List.of());
        when(conversations.findByUserIdOrderByCreatedAtDesc(eq(userId), any())).thenReturn(List.of());
        Path backupRoot = tempDirectory.resolve("episode-backup");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        MemoryBackupJob job = job(profiles, memories, changeLogs, reminders, media, conversations, mapper,
                backupRoot, 1000);

        job.backupAll();
        MemoryBackupJob.PurgeResult result = job.purgeForgottenConversationEvidence(userId,
                List.of("episode-message"), "用户曾因申请表提醒异常而着急");

        assertTrue(result.complete());
        assertEquals(1, result.filesUpdated());
        String state = readStateFromArchive(dayArchive(backupRoot), "user-" + shortHash(userId) + "/state.json");
        assertEquals(0, mapper.readTree(state).path("memories").size());
    }

    /**
     * 老备份包（三表还在时写的）里记忆分散在 coreMemories / workMemories / episodicMemories 三个数组，
     * 而包还有 30 天保留期。清理老包时必须三个都找一遍，否则正文会留在历史备份里。
     */
    @Test
    void purgesLegacyThreeArrayBackupsWrittenBeforeTheMerge() throws Exception {
        String userId = "qq:legacy-user";
        String userHash = shortHash(userId);
        Path backupRoot = tempDirectory.resolve("legacy-backup");
        Files.createDirectories(backupRoot);
        Path archive = backupRoot.resolve("20260901.zip");
        String legacyState = """
                {"userId":"%s",
                 "coreMemories":[{"id":7,"content":"这是必须彻底遗忘的私密目标"}],
                 "workMemories":[{"id":8,"content":"老结构里的中期事项"}],
                 "episodicMemories":[{"id":9,"summary":"老结构里的经历"}],
                 "changeLogs":[{"layer":"CORE","targetId":7,"beforeContent":"x","afterContent":"x"}]}
                """.formatted(userId);
        writeArchive(archive, "user-" + userHash + "/state.json", legacyState);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        MemoryBackupJob job = new MemoryBackupJob(mock(UserProfileRepository.class), mock(MemoryRepository.class),
                mock(MemoryChangeLogRepository.class), mock(ReminderTaskRepository.class),
                mock(StoredMediaRepository.class), mapper, backupRoot.toString(), 30,
                tempDirectory.resolve("stored-media").toString());

        // 老包的层名是 CORE，清理时按 id 在三个数组里都找一遍
        MemoryBackupJob.PurgeResult result = job.purgeForgottenMemory(userId, "CORE", 7L);
        assertTrue(result.complete());

        String rewritten = readStateFromArchive(archive, "user-" + userHash + "/state.json");
        assertFalse(rewritten.contains("这是必须彻底遗忘的私密目标"));
        // 另外两个老数组里的行不受影响（id 不同）
        assertTrue(rewritten.contains("老结构里的中期事项"));
        var root = mapper.readTree(rewritten);
        assertEquals(0, root.path("coreMemories").size());
        assertTrue(root.path("changeLogs").get(0).path("afterContent").isNull());
    }

    /**
     * 三表合一后 {@code EpisodicMemoryRepository} 没了，原来"带 conversation + episodic"的兼容构造器
     * 与 {@code @Autowired} 主构造器签名重合，只能留一个——测试统一走主构造器，把默认值显式传进去。
     */
    private MemoryBackupJob job(UserProfileRepository profiles,
                                MemoryRepository memories,
                                MemoryChangeLogRepository changeLogs,
                                ReminderTaskRepository reminders,
                                StoredMediaRepository media,
                                ConversationMemoryRepository conversations,
                                ObjectMapper mapper,
                                Path backupRoot,
                                int conversationBackupLimit) {
        return new MemoryBackupJob(profiles, memories, changeLogs, reminders, media, conversations, mapper,
                backupRoot.toString(), 30, conversationBackupLimit,
                tempDirectory.resolve("stored-media").toString(), 5000, "Asia/Shanghai");
    }

    /**
     * 备份产物是 {@code backup/<yyyyMMdd>.zip}——当天目录在打包后就被删掉了，
     * 所以验状态只能从压缩包里读（原来这几个用例去列当天目录，必然 NoSuchFile）。
     */
    private Path dayArchive(Path backupRoot) {
        return backupRoot.resolve(LocalDate.now(BACKUP_ZONE)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".zip");
    }

    private String readStateFromArchive(Path archive, String entryName) throws Exception {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new AssertionError("压缩包里没有 " + entryName + "，实际条目：" + zip.stream()
                        .map(ZipEntry::getName).toList());
            }
            try (var input = zip.getInputStream(entry)) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private void writeArchive(Path archive, String entryName, String content) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            out.putNextEntry(new ZipEntry(entryName));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    /** 与 MemoryBackupJob 的 user-<hash> 目录命名保持一致（sha256 前 12 位） */
    private String shortHash(String value) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest).substring(0, 12);
    }
}
