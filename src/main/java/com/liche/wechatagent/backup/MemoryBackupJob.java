package com.liche.wechatagent.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.liche.wechatagent.media.StoredMedia;
import com.liche.wechatagent.media.StoredMediaRepository;
import com.liche.wechatagent.memory.ConversationMemory;
import com.liche.wechatagent.memory.ConversationMemoryRepository;
import com.liche.wechatagent.memory.EpisodicMemory;
import com.liche.wechatagent.memory.EpisodicMemoryRepository;
import com.liche.wechatagent.memory.MemoryArchiveRepository;
import com.liche.wechatagent.memory.MemoryChangeLogRepository;
import com.liche.wechatagent.memory.UserCoreMemoryRepository;
import com.liche.wechatagent.memory.UserWorkMemoryRepository;
import com.liche.wechatagent.reminder.ReminderTaskRepository;
import com.liche.wechatagent.user.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 每日备份用户记忆、提醒和已保存资料。
 *
 * <p>磁盘上的样子：`backup/<yyyyMMdd>.zip` 每天一个压缩包，里面是 `user-<hash>/state.json`（明文 JSON）；
 * 媒体本体按 sha256 存在**共享**的 `backup/media/<sha256>.bin`，全网只有一份、不再每天复制一遍
 * （这是唯一会长大的部分，所以按内容寻址去重，并在清理旧备份时回收没人引用的）。
 */
@Component
public class MemoryBackupJob {

    private static final Logger log = LoggerFactory.getLogger(MemoryBackupJob.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int DEFAULT_CONVERSATION_BACKUP_LIMIT = 10_000;
    private static final int DEFAULT_CHANGE_LOG_LIMIT = 5_000;
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    public record PurgeResult(int filesUpdated, boolean complete) {
    }

    private final UserProfileRepository userProfileRepository;
    private final UserCoreMemoryRepository coreRepository;
    private final UserWorkMemoryRepository workRepository;
    private final MemoryArchiveRepository archiveRepository;
    private final MemoryChangeLogRepository changeLogRepository;
    private final ReminderTaskRepository reminderRepository;
    private final StoredMediaRepository storedMediaRepository;
    private final ConversationMemoryRepository conversationMemoryRepository;
    private final EpisodicMemoryRepository episodicMemoryRepository;
    private final ObjectMapper objectMapper;
    private final Path backupDir;
    private final Path mediaRoot;
    private final int retentionDays;
    private final int conversationBackupLimit;
    private final int changeLogLimit;
    private final ZoneId zone;

    @org.springframework.beans.factory.annotation.Autowired
    public MemoryBackupJob(UserProfileRepository userProfileRepository,
                           UserCoreMemoryRepository coreRepository,
                           UserWorkMemoryRepository workRepository,
                           MemoryArchiveRepository archiveRepository,
                           MemoryChangeLogRepository changeLogRepository,
                           ReminderTaskRepository reminderRepository,
                           StoredMediaRepository storedMediaRepository,
                           ConversationMemoryRepository conversationMemoryRepository,
                           EpisodicMemoryRepository episodicMemoryRepository,
                           ObjectMapper objectMapper,
                           @Value("${backup.dir:backup}") String backupDir,
                           @Value("${backup.retention-days:30}") int retentionDays,
                           @Value("${backup.conversation-limit:10000}") int conversationBackupLimit,
                            @Value("${media.storage.root:stored-media}") String mediaRoot,
                            @Value("${backup.change-log-limit:5000}") int changeLogLimit,
                            @Value("${app.time-zone:Asia/Shanghai}") String timeZoneId) {
        this(userProfileRepository, coreRepository, workRepository, archiveRepository, changeLogRepository,
                reminderRepository, storedMediaRepository, conversationMemoryRepository, episodicMemoryRepository,
                objectMapper, backupDir,
                retentionDays, conversationBackupLimit, mediaRoot, changeLogLimit, timeZoneId, true);
    }

    /** Backwards-compatible constructor retained for focused tests and older embedders. */
    public MemoryBackupJob(UserProfileRepository userProfileRepository,
                           UserCoreMemoryRepository coreRepository,
                           UserWorkMemoryRepository workRepository,
                           MemoryArchiveRepository archiveRepository,
                           MemoryChangeLogRepository changeLogRepository,
                           ReminderTaskRepository reminderRepository,
                           StoredMediaRepository storedMediaRepository,
                           ObjectMapper objectMapper,
                           String backupDir,
                           int retentionDays,
                           String mediaRoot) {
        this(userProfileRepository, coreRepository, workRepository, archiveRepository, changeLogRepository,
                reminderRepository, storedMediaRepository, null, null, objectMapper, backupDir, retentionDays,
                DEFAULT_CONVERSATION_BACKUP_LIMIT, mediaRoot, DEFAULT_CHANGE_LOG_LIMIT, DEFAULT_ZONE.getId());
    }

    /** Compatibility constructor for callers that include durable conversation evidence. */
    public MemoryBackupJob(UserProfileRepository userProfileRepository,
                           UserCoreMemoryRepository coreRepository,
                           UserWorkMemoryRepository workRepository,
                           MemoryArchiveRepository archiveRepository,
                           MemoryChangeLogRepository changeLogRepository,
                           ReminderTaskRepository reminderRepository,
                           StoredMediaRepository storedMediaRepository,
                           ConversationMemoryRepository conversationMemoryRepository,
                           ObjectMapper objectMapper,
                           String backupDir,
                           int retentionDays,
                           int conversationBackupLimit,
                           String mediaRoot) {
        this(userProfileRepository, coreRepository, workRepository, archiveRepository, changeLogRepository,
                reminderRepository, storedMediaRepository, conversationMemoryRepository, null, objectMapper,
                backupDir, retentionDays, conversationBackupLimit, mediaRoot, DEFAULT_CHANGE_LOG_LIMIT,
                DEFAULT_ZONE.getId());
    }

    /** Compatibility constructor for callers that include conversation and episodic evidence. */
    public MemoryBackupJob(UserProfileRepository userProfileRepository,
                           UserCoreMemoryRepository coreRepository,
                           UserWorkMemoryRepository workRepository,
                           MemoryArchiveRepository archiveRepository,
                           MemoryChangeLogRepository changeLogRepository,
                           ReminderTaskRepository reminderRepository,
                           StoredMediaRepository storedMediaRepository,
                           ConversationMemoryRepository conversationMemoryRepository,
                           EpisodicMemoryRepository episodicMemoryRepository,
                           ObjectMapper objectMapper,
                           String backupDir,
                           int retentionDays,
                           int conversationBackupLimit,
                           String mediaRoot) {
        this(userProfileRepository, coreRepository, workRepository, archiveRepository, changeLogRepository,
                reminderRepository, storedMediaRepository, conversationMemoryRepository, episodicMemoryRepository,
                objectMapper, backupDir, retentionDays, conversationBackupLimit, mediaRoot,
                DEFAULT_CHANGE_LOG_LIMIT, DEFAULT_ZONE.getId(), true);
    }

    private MemoryBackupJob(UserProfileRepository userProfileRepository,
                            UserCoreMemoryRepository coreRepository,
                            UserWorkMemoryRepository workRepository,
                            MemoryArchiveRepository archiveRepository,
                            MemoryChangeLogRepository changeLogRepository,
                            ReminderTaskRepository reminderRepository,
                            StoredMediaRepository storedMediaRepository,
                            ConversationMemoryRepository conversationMemoryRepository,
                            EpisodicMemoryRepository episodicMemoryRepository,
                            ObjectMapper objectMapper,
                            String backupDir,
                            int retentionDays,
                            int conversationBackupLimit,
                            String mediaRoot,
                            int changeLogLimit,
                            String timeZoneId,
                            boolean initializationMarker) {
        this.userProfileRepository = userProfileRepository;
        this.coreRepository = coreRepository;
        this.workRepository = workRepository;
        this.archiveRepository = archiveRepository;
        this.changeLogRepository = changeLogRepository;
        this.reminderRepository = reminderRepository;
        this.storedMediaRepository = storedMediaRepository;
        this.conversationMemoryRepository = conversationMemoryRepository;
        this.episodicMemoryRepository = episodicMemoryRepository;
        this.objectMapper = objectMapper;
        this.backupDir = Path.of(backupDir).toAbsolutePath().normalize();
        this.mediaRoot = Path.of(mediaRoot).toAbsolutePath().normalize();
        this.retentionDays = Math.max(1, retentionDays);
        this.conversationBackupLimit = Math.max(100, conversationBackupLimit);
        this.changeLogLimit = Math.max(100, changeLogLimit);
        this.zone = parseZone(timeZoneId);
    }

    @Scheduled(cron = "${backup.cron:0 0 3 * * ?}", zone = "${app.time-zone:Asia/Shanghai}")
    public void backupAll() {
        try {
            Path dayDir = checkedChild(backupDir, LocalDate.now(zone).format(DAY));
            Files.createDirectories(dayDir);
            int userCount = 0;
            for (var profile : userProfileRepository.findAll()) {
                backupUser(dayDir, profile.getUserId());
                userCount++;
            }
            // 打完包就把目录删掉：每天只留一个压缩包（媒体本体在 backup/media 里共享一份）
            Path archive = checkedChild(backupDir, LocalDate.now(zone).format(DAY) + ".zip");
            zipDay(dayDir, archive);
            deleteRecursively(dayDir);
            log.info("每日记忆与资料备份完成: {} ({} 个用户)", archive, userCount);
            prune();
        } catch (Exception exception) {
            log.error("每日记忆备份失败", exception);
        }
    }

    /** 当天目录打成压缩包；里面就是 user-&lt;hash&gt;/state.json 明文，解压即可读 */
    private void zipDay(Path dayDir, Path archive) throws IOException {
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
            move(temporary, archive, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Redacts a forgotten memory from every existing local backup for the same user. */
    public PurgeResult purgeForgottenMemory(String userId, String layer, Long targetId) {
        if (userId == null || userId.isBlank() || layer == null || layer.isBlank() || targetId == null) {
            throw new IllegalArgumentException("遗忘备份清理参数不完整");
        }
        if (!Files.isDirectory(backupDir)) {
            return new PurgeResult(0, true);
        }
        List<Path> archives = backupDayArchives();
        if (archives == null) {
            return new PurgeResult(0, false);
        }
        String entryName = "user-" + shortHash(userId) + "/state.json";
        int updated = 0;
        boolean complete = true;
        for (Path archive : archives) {
            try {
                if (editStateInArchive(archive, entryName,
                        stateFile -> redactBackupState(stateFile, userId, layer, targetId))) {
                    updated++;
                }
            } catch (Exception exception) {
                complete = false;
                log.warn("清理历史备份失败 userHash={} layer={} targetId={} day={} reason={}", shortHash(userId), layer,
                        targetId, archive.getFileName(), exception.getClass().getSimpleName());
            }
        }
        return new PurgeResult(updated, complete);
    }

    /** Redacts conversation evidence that supported a user-forgotten memory from local snapshots. */
    public PurgeResult purgeForgottenConversationEvidence(String userId, List<String> sourceMessageIds,
                                                          String rememberedContent) {
        Set<String> sourceIds = normalizeMessageIds(sourceMessageIds);
        String normalizedContent = normalizeForComparison(rememberedContent);
        if (userId == null || userId.isBlank() || (sourceIds.isEmpty() && normalizedContent.length() < 6)) {
            return new PurgeResult(0, true);
        }
        if (!Files.isDirectory(backupDir)) {
            return new PurgeResult(0, true);
        }
        List<Path> archives = backupDayArchives();
        if (archives == null) {
            return new PurgeResult(0, false);
        }
        String entryName = "user-" + shortHash(userId) + "/state.json";
        int updated = 0;
        boolean complete = true;
        for (Path archive : archives) {
            try {
                if (editStateInArchive(archive, entryName,
                        stateFile -> redactConversationEvidence(stateFile, userId, sourceIds, normalizedContent))) {
                    updated++;
                }
            } catch (Exception exception) {
                complete = false;
                log.warn("清理历史对话备份失败 userHash={} day={} reason={}", shortHash(userId),
                        archive.getFileName(), exception.getClass().getSimpleName());
            }
        }
        return new PurgeResult(updated, complete);
    }

    /** 所有历史备份压缩包（backup/&lt;yyyyMMdd&gt;.zip）；列不出来时返回 null（调用方据此报"没做全"） */
    private List<Path> backupDayArchives() {
        try (Stream<Path> paths = Files.list(backupDir)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("\\d{8}\\.zip"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException exception) {
            log.warn("列出历史备份失败 reason={}", exception.getClass().getSimpleName());
            return null;
        }
    }

    /** 备份状态的改写逻辑（忘掉记忆 / 清理对话证据）都作用在一个普通文件上 */
    @FunctionalInterface
    private interface StateEditor {
        boolean edit(Path stateFile) throws IOException;
    }

    /**
     * 把压缩包里的 user-&lt;hash&gt;/state.json 取到临时文件、交给 editor 改，改过才把压缩包重写一遍。
     * 没这个条目、或什么都没改时不动压缩包。
     */
    private boolean editStateInArchive(Path archive, String entryName, StateEditor editor) throws IOException {
        if (!Files.isRegularFile(archive)) {
            return false;
        }
        Path workDir = Files.createTempDirectory("backup-purge-");
        try {
            Path stateFile = workDir.resolve("state.json");
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                ZipEntry entry = zip.getEntry(entryName);
                if (entry == null) {
                    return false;
                }
                try (InputStream input = zip.getInputStream(entry)) {
                    Files.copy(input, stateFile, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (!editor.edit(stateFile)) {
                return false;
            }
            rewriteArchiveEntry(archive, entryName, stateFile);
            return true;
        } finally {
            deleteRecursively(workDir);
        }
    }

    /** 重写压缩包里的一个条目：其余条目原样搬运，只有目标条目换成新内容 */
    private void rewriteArchiveEntry(Path archive, String entryName, Path replacement) throws IOException {
        Path temporary = Files.createTempFile(backupDir, ".backup-", ".zip.tmp");
        try {
            try (ZipFile zip = new ZipFile(archive.toFile());
                 ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temporary))) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    out.putNextEntry(new ZipEntry(entry.getName()));
                    if (entry.getName().equals(entryName)) {
                        Files.copy(replacement, out);
                    } else {
                        try (InputStream input = zip.getInputStream(entry)) {
                            input.transferTo(out);
                        }
                    }
                    out.closeEntry();
                }
            }
            move(temporary, archive, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void backupUser(Path dayDir, String userId) throws IOException {
        Path userDir = checkedChild(dayDir, "user-" + shortHash(userId));
        Files.createDirectories(userDir);
        List<StoredMedia> media = storedMediaRepository.findByUserIdOrderByCreatedAtAsc(userId);
        List<ConversationMemory> conversations = conversationEvidenceForBackup(userId);
        ObjectNode node = buildBackupState(userId, media, conversations);
        writeAtomically(userDir.resolve("state.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node));
    }

    // Collects all user-scoped records and media artifacts into one backup document.
    private ObjectNode buildBackupState(String userId, List<StoredMedia> media,
                                        List<ConversationMemory> conversations) throws IOException {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("userId", userId);
        node.set("profile", objectMapper.valueToTree(userProfileRepository.findById(userId).orElse(null)));
        node.set("coreMemories", objectMapper.valueToTree(coreRepository.findByUserIdOrderByCreatedAtAsc(userId)));
        node.set("workMemories", objectMapper.valueToTree(workRepository.findByUserIdAndArchivedFalse(userId)));
        node.set("archives", objectMapper.valueToTree(archiveRepository.findByUserIdOrderByCreatedAtDesc(userId)));
        node.set("changeLogs", objectMapper.valueToTree(
                changeLogRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, changeLogLimit))));
        node.set("conversationMemories", objectMapper.valueToTree(conversations));
        node.set("episodicMemories", objectMapper.valueToTree(episodicEvidenceForBackup(userId)));
        node.put("conversationMemoryBackupLimit", conversationBackupLimit);
        node.set("reminders", objectMapper.valueToTree(reminderRepository.findByUserIdAndStatus(userId, "PENDING")));
        node.set("storedMedia", objectMapper.valueToTree(media));
        node.set("mediaArtifacts", backupMedia(userId, media));
        return node;
    }

    private List<ConversationMemory> conversationEvidenceForBackup(String userId) {
        if (conversationMemoryRepository == null || userId == null || userId.isBlank()) {
            return List.of();
        }
        try {
            List<ConversationMemory> records = conversationMemoryRepository.findByUserIdOrderByCreatedAtDesc(userId,
                    PageRequest.of(0, conversationBackupLimit));
            if (records == null) {
                return List.of();
            }
            return records.stream()
                    .filter(record -> record != null && userId.equals(record.getUserId()))
                    .filter(record -> record.getExpiresAt() == null || record.getExpiresAt().isAfter(LocalDateTime.now(zone)))
                    .sorted(Comparator.comparing(ConversationMemory::getCreatedAt,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
        } catch (Exception exception) {
            log.error("备份持久化对话证据失败 userHash={}", shortHash(userId), exception);
            throw new IllegalStateException("备份对话证据失败，已中止本次备份以避免写出空备份", exception);
        }
    }

    private List<EpisodicMemory> episodicEvidenceForBackup(String userId) {
        if (episodicMemoryRepository == null || userId == null || userId.isBlank()) {
            return List.of();
        }
        try {
            var records = episodicMemoryRepository.findByUserIdOrderByOccurredAtDesc(userId);
            return records == null ? List.of() : records.stream()
                    .filter(record -> record != null && userId.equals(record.getUserId()))
                    .toList();
        } catch (Exception exception) {
            log.error("备份情景记忆失败 userHash={}", shortHash(userId), exception);
            throw new IllegalStateException("备份情景记忆失败，已中止本次备份以避免写出空备份", exception);
        }
    }

    /**
     * 媒体本体按**内容寻址**存进共享目录 `backup/media/&lt;sha256&gt;.bin`：同一份内容只存一次，
     * 每天备份同一张图不会再多占一份（以前是复制进当天的目录、30 天就是 30 份）。
     * 大小一致就认为已经存过，不再重复复制与校验。
     */
    private ArrayNode backupMedia(String userId, List<StoredMedia> media) throws IOException {
        Path blobDir = checkedChild(backupDir, "media");
        Files.createDirectories(blobDir);
        ArrayNode artifacts = objectMapper.createArrayNode();
        for (StoredMedia item : media) {
            ObjectNode artifact = artifacts.addObject();
            artifact.put("mediaId", item.getId());
            artifact.put("expectedSha256", item.getSha256());
            try {
                Path source = resolveOwnedMedia(userId, item.getRelativePath());
                if (!Files.isRegularFile(source)) {
                    artifact.put("status", "MISSING_SOURCE");
                    continue;
                }
                if (item.getSha256() == null || item.getSha256().isBlank()) {
                    artifact.put("status", "MISSING_HASH");
                    continue;
                }
                String targetName = item.getSha256().toLowerCase() + ".bin";
                Path target = checkedChild(blobDir, targetName);
                if (Files.isRegularFile(target) && Files.size(target) == Files.size(source)) {
                    artifact.put("status", "OK");
                    artifact.put("path", "media/" + targetName);
                    artifact.put("sizeBytes", Files.size(target));
                    artifact.put("reused", true);
                    continue;
                }
                copyAtomically(source, target);
                String actualHash = sha256(target);
                if (!actualHash.equalsIgnoreCase(item.getSha256())) {
                    Files.deleteIfExists(target);
                    artifact.put("status", "HASH_MISMATCH");
                    artifact.put("actualSha256", actualHash);
                    continue;
                }
                artifact.put("status", "OK");
                artifact.put("path", "media/" + targetName);
                artifact.put("sizeBytes", Files.size(target));
            } catch (Exception exception) {
                artifact.put("status", "COPY_FAILED");
                artifact.put("reason", exception.getClass().getSimpleName());
                log.warn("备份用户资料失败 userHash={} mediaId={} reason={}", shortHash(userId), item.getId(),
                        exception.getClass().getSimpleName());
            }
        }
        return artifacts;
    }

    private boolean redactBackupState(Path stateFile, String userId, String layer, Long targetId) throws IOException {
        ObjectNode state = readOwnedState(stateFile, userId);
        boolean changed = removeMemoryRecord(state, layer, targetId);
        changed |= redactChangeLogs(state, layer, targetId);
        if ("WORK".equals(layer)) {
            for (Long archiveId : removeArchivesReferencingWork(state, targetId)) {
                changed = true;
                changed |= redactChangeLogs(state, "ARCHIVE", archiveId);
            }
        }
        if (changed) {
            writeAtomically(stateFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state));
        }
        return changed;
    }

    private boolean redactConversationEvidence(Path stateFile, String userId, Set<String> sourceMessageIds,
                                               String rememberedContent) throws IOException {
        ObjectNode state = readOwnedState(stateFile, userId);
        boolean changed = removeConversationRecords(state, sourceMessageIds, rememberedContent);
        changed |= removeEpisodicRecords(state, sourceMessageIds, rememberedContent);
        if (!changed) {
            return false;
        }
        writeAtomically(stateFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state));
        return true;
    }

    private ObjectNode readOwnedState(Path stateFile, String userId) throws IOException {
        JsonNode parsed = objectMapper.readTree(Files.readString(stateFile, StandardCharsets.UTF_8));
        if (!(parsed instanceof ObjectNode state)) {
            throw new IOException("备份状态文件不是对象");
        }
        if (!userId.equals(state.path("userId").asText())) {
            throw new IOException("备份状态用户不匹配");
        }
        return state;
    }

    private boolean removeMemoryRecord(ObjectNode state, String layer, Long targetId) {
        String collection = switch (layer) {
            case "CORE" -> "coreMemories";
            case "WORK" -> "workMemories";
            case "ARCHIVE" -> "archives";
            default -> "";
        };
        ArrayNode records = array(state, collection);
        if (records == null) {
            return false;
        }
        boolean changed = false;
        for (int index = records.size() - 1; index >= 0; index--) {
            if (targetId.equals(records.get(index).path("id").asLong())) {
                records.remove(index);
                changed = true;
            }
        }
        return changed;
    }

    private boolean redactChangeLogs(ObjectNode state, String layer, Long targetId) {
        ArrayNode logs = array(state, "changeLogs");
        if (logs == null) {
            return false;
        }
        boolean changed = false;
        for (JsonNode node : logs) {
            if (!(node instanceof ObjectNode logEntry)
                    || !layer.equals(logEntry.path("layer").asText())
                    || !targetId.equals(logEntry.path("targetId").asLong())) {
                continue;
            }
            if (!logEntry.path("beforeContent").isNull() || !logEntry.path("afterContent").isNull()) {
                logEntry.putNull("beforeContent");
                logEntry.putNull("afterContent");
                changed = true;
            }
        }
        return changed;
    }

    private boolean removeConversationRecords(ObjectNode state, Set<String> sourceMessageIds,
                                              String rememberedContent) {
        ArrayNode records = array(state, "conversationMemories");
        if (records == null) {
            return false;
        }
        boolean changed = false;
        for (int index = records.size() - 1; index >= 0; index--) {
            JsonNode record = records.get(index);
            if (!conversationMatches(record, sourceMessageIds, rememberedContent)) {
                continue;
            }
            records.remove(index);
            changed = true;
        }
        return changed;
    }

    private boolean conversationMatches(JsonNode record, Set<String> sourceMessageIds, String rememberedContent) {
        if (record == null) {
            return false;
        }
        if (sourceMessageIds != null && !sourceMessageIds.isEmpty()) {
            return backupSourceMessageIds(record).stream().anyMatch(sourceMessageIds::contains);
        }
        return rememberedContent != null && rememberedContent.length() >= 6
                && contentContains(record.path("content").asText(""), rememberedContent);
    }

    private boolean removeEpisodicRecords(ObjectNode state, Set<String> sourceMessageIds,
                                          String rememberedContent) {
        ArrayNode records = array(state, "episodicMemories");
        if (records == null) {
            return false;
        }
        boolean changed = false;
        for (int index = records.size() - 1; index >= 0; index--) {
            JsonNode record = records.get(index);
            boolean sourceMatch = sourceMessageIds != null && !sourceMessageIds.isEmpty()
                    && backupSourceMessageIds(record).stream().anyMatch(sourceMessageIds::contains);
            boolean contentMatch = (sourceMessageIds == null || sourceMessageIds.isEmpty())
                    && rememberedContent != null && rememberedContent.length() >= 6
                    && contentContains(record.path("summary").asText(""), rememberedContent);
            if (sourceMatch || contentMatch) {
                records.remove(index);
                changed = true;
            }
        }
        return changed;
    }

    private Set<String> backupSourceMessageIds(JsonNode record) {
        Set<String> ids = new LinkedHashSet<>();
        JsonNode value = record.path("sourceMessageIds");
        if (value.isArray()) {
            for (JsonNode item : value) {
                String text = item.asText("").trim();
                if (!text.isBlank()) {
                    ids.add(text);
                }
            }
            return ids;
        }
        String serialized = value.asText("");
        for (String item : serialized.split("\\|")) {
            String text = item.trim();
            if (!text.isBlank()) {
                ids.add(text);
            }
        }
        return ids;
    }

    private Set<String> normalizeMessageIds(List<String> values) {
        Set<String> ids = new LinkedHashSet<>();
        if (values == null) {
            return ids;
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            ids.add(value.replace('|', '_').trim());
        }
        return ids;
    }

    private boolean contentContains(String content, String target) {
        String normalized = normalizeForComparison(content);
        if (normalized.length() < 6) {
            return false;
        }
        if (normalized.contains(target) || target.contains(normalized)) {
            return true;
        }
        int maximum = Math.min(24, Math.min(normalized.length(), target.length()));
        for (int length = maximum; length >= 6; length--) {
            for (int start = 0; start + length <= normalized.length(); start++) {
                if (target.contains(normalized.substring(start, start + length))) {
                    return true;
                }
            }
        }
        return false;
    }

    private String normalizeForComparison(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\p{P}\\p{Z}\\s]+", "")
                .trim();
    }

    private List<Long> removeArchivesReferencingWork(ObjectNode state, Long workId) {
        ArrayNode archives = array(state, "archives");
        if (archives == null) {
            return List.of();
        }
        List<Long> removedArchiveIds = new java.util.ArrayList<>();
        for (int index = archives.size() - 1; index >= 0; index--) {
            JsonNode archive = archives.get(index);
            if (!archiveReferencesWork(archive, workId)) {
                continue;
            }
            long archiveId = archive.path("id").asLong();
            if (archiveId > 0) {
                removedArchiveIds.add(archiveId);
            }
            archives.remove(index);
        }
        return removedArchiveIds;
    }

    private boolean archiveReferencesWork(JsonNode archive, Long workId) {
        try {
            JsonNode originalIds = objectMapper.readTree(archive.path("originalIds").asText("[]"));
            for (JsonNode id : originalIds) {
                if (workId.equals(id.asLong())) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // A malformed archive is left untouched rather than risking unrelated data removal.
        }
        return false;
    }

    private ArrayNode array(ObjectNode state, String field) {
        JsonNode node = state.get(field);
        return node instanceof ArrayNode array ? array : null;
    }

    private Path resolveOwnedMedia(String userId, String relativePath) {
        Path path = checkedChild(mediaRoot, relativePath);
        Path userRoot = checkedChild(mediaRoot, userDirectoryName(userId));
        if (!path.startsWith(userRoot)) {
            throw new IllegalArgumentException("资料路径不属于当前用户目录");
        }
        return path;
    }

    private void writeAtomically(Path target, String content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".state-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void copyAtomically(Path source, Path target) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".media-", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void move(Path source, Path target, StandardCopyOption... options) throws IOException {
        try {
            CopyOption[] atomicOptions = new CopyOption[options.length + 1];
            atomicOptions[0] = StandardCopyOption.ATOMIC_MOVE;
            System.arraycopy(options, 0, atomicOptions, 1, options.length);
            Files.move(source, target, atomicOptions);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, options);
        }
    }

    private Path checkedChild(Path parent, String child) {
        Path normalizedParent = parent.toAbsolutePath().normalize();
        Path resolved = normalizedParent.resolve(child).normalize();
        if (!resolved.startsWith(normalizedParent)) {
            throw new IllegalArgumentException("非法备份路径");
        }
        return resolved;
    }

    private String userDirectoryName(String userId) {
        String readable = userId == null ? "" : userId.replaceAll("[^A-Za-z0-9_-]", "_");
        if (readable.isBlank()) {
            readable = "user";
        }
        if (readable.length() > 48) {
            readable = readable.substring(0, 48);
        }
        return readable + "-" + shortHash(userId);
    }

    private String shortHash(String value) {
        return sha256(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8)).substring(0, 12);
    }

    private String sha256(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前环境不支持 SHA-256", exception);
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前环境不支持 SHA-256", exception);
        }
    }

    private void prune() throws IOException {
        if (!Files.isDirectory(backupDir)) {
            return;
        }
        LocalDate cutoff = LocalDate.now(zone).minusDays(retentionDays);
        boolean removed = false;
        // 现在的备份是 backup/<yyyyMMdd>.zip；历史遗留的 <yyyyMMdd>/ 目录（老版本写的）也一起清掉
        List<Path> expired;
        try (Stream<Path> entries = Files.list(backupDir)) {
            expired = entries.filter(path -> {
                String name = path.getFileName().toString();
                String day = name.endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
                if (!day.matches("\\d{8}")) {
                    return false;
                }
                try {
                    return LocalDate.parse(day, DAY).isBefore(cutoff);
                } catch (Exception exception) {
                    return false;
                }
            }).sorted(Comparator.comparing(Path::toString)).toList();
        }
        for (Path path : expired) {
            try {
                if (Files.isDirectory(path)) {
                    deleteRecursively(path);
                } else {
                    Files.deleteIfExists(path);
                }
                removed = true;
                log.info("清理过期备份: {}", path);
            } catch (IOException exception) {
                log.warn("清理备份失败: {}", path, exception.getClass().getSimpleName());
            }
        }
        if (removed) {
            pruneMediaBlobs();
        }
    }

    /** 共享媒体库里已经不被任何一份备份引用的文件清掉，免得它只增不减 */
    private void pruneMediaBlobs() {
        Path blobDir = backupDir.resolve("media");
        if (!Files.isDirectory(blobDir)) {
            return;
        }
        List<Path> archives = backupDayArchives();
        if (archives == null) {
            return;
        }
        Set<String> referenced = new LinkedHashSet<>();
        for (Path archive : archives) {
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.getName().endsWith("/state.json")) {
                        continue;
                    }
                    try (InputStream input = zip.getInputStream(entry)) {
                        collectReferencedBlobs(objectMapper.readTree(input), referenced);
                    }
                }
            } catch (Exception exception) {
                // 有压缩包读不动就整个跳过，宁可不删
                log.warn("读取备份失败，跳过媒体清理: {} ({})", archive, exception.getClass().getSimpleName());
                return;
            }
        }
        try (Stream<Path> blobs = Files.list(blobDir)) {
            blobs.filter(Files::isRegularFile).forEach(blob -> {
                if (referenced.contains(blob.getFileName().toString())) {
                    return;
                }
                try {
                    Files.deleteIfExists(blob);
                    log.info("清理不再被引用的媒体副本: {}", blob.getFileName());
                } catch (IOException exception) {
                    log.warn("清理媒体副本失败: {}", blob.getFileName());
                }
            });
        } catch (IOException exception) {
            log.warn("扫描媒体副本失败: {}", exception.getClass().getSimpleName());
        }
    }

    private void collectReferencedBlobs(JsonNode state, Set<String> referenced) {
        JsonNode artifacts = state.path("mediaArtifacts");
        if (!artifacts.isArray()) {
            return;
        }
        for (JsonNode artifact : artifacts) {
            String path = artifact.path("path").asText("");
            if (path.startsWith("media/")) {
                referenced.add(path.substring("media/".length()));
            }
        }
    }

    private void deleteRecursively(Path directory) throws IOException {
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    log.warn("删除备份文件失败: {}", path);
                }
            });
        }
    }

    private ZoneId parseZone(String value) {
        try {
            return ZoneId.of(value);
        } catch (RuntimeException ignored) {
            return DEFAULT_ZONE;
        }
    }
}
