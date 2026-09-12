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

/** 每日备份用户记忆、提醒和已保存资料，并以校验和记录可恢复的媒体副本。 */
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
            log.info("每日记忆与资料备份完成: {} ({} 个用户)", dayDir, userCount);
            prune();
        } catch (Exception exception) {
            log.error("每日记忆备份失败", exception);
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
        List<Path> dayDirectories = backupDayDirectories(userId, layer, targetId);
        if (dayDirectories == null) {
            return new PurgeResult(0, false);
        }
        int updated = 0;
        boolean complete = true;
        for (Path dayDirectory : dayDirectories) {
            Path userDirectory = checkedChild(dayDirectory, "user-" + shortHash(userId));
            Path stateFile = checkedChild(userDirectory, "state.json");
            if (!Files.isRegularFile(stateFile)) {
                continue;
            }
            try {
                if (redactBackupState(stateFile, userId, layer, targetId)) {
                    updated++;
                }
            } catch (Exception exception) {
                complete = false;
                log.warn("清理历史备份失败 userHash={} layer={} targetId={} day={} reason={}", shortHash(userId), layer,
                        targetId, dayDirectory.getFileName(), exception.getClass().getSimpleName());
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
        List<Path> dayDirectories = backupDayDirectories(userId, "CONVERSATION", null);
        if (dayDirectories == null) {
            return new PurgeResult(0, false);
        }
        int updated = 0;
        boolean complete = true;
        for (Path dayDirectory : dayDirectories) {
            Path userDirectory = checkedChild(dayDirectory, "user-" + shortHash(userId));
            Path stateFile = checkedChild(userDirectory, "state.json");
            if (!Files.isRegularFile(stateFile)) {
                continue;
            }
            try {
                if (redactConversationEvidence(stateFile, userId, sourceIds, normalizedContent)) {
                    updated++;
                }
            } catch (Exception exception) {
                complete = false;
                log.warn("清理历史对话备份失败 userHash={} day={} reason={}", shortHash(userId),
                        dayDirectory.getFileName(), exception.getClass().getSimpleName());
            }
        }
        return new PurgeResult(updated, complete);
    }

    private List<Path> backupDayDirectories(String userId, String layer, Long targetId) {
        try (Stream<Path> paths = Files.list(backupDir)) {
            return paths.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().matches("\\d{8}"))
                    .toList();
        } catch (IOException exception) {
            log.warn("列出历史备份失败 userHash={} layer={} targetId={} reason={}", shortHash(userId), layer, targetId,
                    exception.getClass().getSimpleName());
            return null;
        }
    }

    private void backupUser(Path dayDir, String userId) throws IOException {
        Path userDir = checkedChild(dayDir, "user-" + shortHash(userId));
        Files.createDirectories(userDir);
        List<StoredMedia> media = storedMediaRepository.findByUserIdOrderByCreatedAtAsc(userId);
        List<ConversationMemory> conversations = conversationEvidenceForBackup(userId);
        ObjectNode node = buildBackupState(userId, userDir, media, conversations);
        writeAtomically(userDir.resolve("state.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node));
    }

    // Collects all user-scoped records and media artifacts into one backup document.
    private ObjectNode buildBackupState(String userId, Path userDir, List<StoredMedia> media,
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
        node.set("mediaArtifacts", backupMedia(userDir, userId, media));
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

    private ArrayNode backupMedia(Path userDir, String userId, List<StoredMedia> media) throws IOException {
        Path artifactDir = checkedChild(userDir, "media");
        Files.createDirectories(artifactDir);
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
                String targetName = item.getId() + "-" + item.getSha256() + ".bin";
                Path target = checkedChild(artifactDir, targetName);
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
        try (Stream<Path> dirs = Files.list(backupDir)) {
            dirs.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().matches("\\d{8}"))
                    .filter(path -> {
                        try {
                            return LocalDate.parse(path.getFileName().toString(), DAY).isBefore(cutoff);
                        } catch (Exception exception) {
                            return false;
                        }
                    })
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(dir -> {
                        try {
                            deleteRecursively(dir);
                            log.info("清理过期备份: {}", dir);
                        } catch (IOException exception) {
                            log.warn("清理备份失败: {}", dir, exception.getClass().getSimpleName());
                        }
                    });
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
