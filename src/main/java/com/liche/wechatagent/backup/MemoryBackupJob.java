package com.liche.wechatagent.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.liche.wechatagent.media.StoredMedia;
import com.liche.wechatagent.media.StoredMediaRepository;
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
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/** 每日备份用户记忆、提醒和已保存资料，并以校验和记录可恢复的媒体副本。 */
@Component
public class MemoryBackupJob {

    private static final Logger log = LoggerFactory.getLogger(MemoryBackupJob.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final UserProfileRepository userProfileRepository;
    private final UserCoreMemoryRepository coreRepository;
    private final UserWorkMemoryRepository workRepository;
    private final MemoryArchiveRepository archiveRepository;
    private final MemoryChangeLogRepository changeLogRepository;
    private final ReminderTaskRepository reminderRepository;
    private final StoredMediaRepository storedMediaRepository;
    private final ObjectMapper objectMapper;
    private final Path backupDir;
    private final Path mediaRoot;
    private final int retentionDays;

    public MemoryBackupJob(UserProfileRepository userProfileRepository,
                           UserCoreMemoryRepository coreRepository,
                           UserWorkMemoryRepository workRepository,
                           MemoryArchiveRepository archiveRepository,
                           MemoryChangeLogRepository changeLogRepository,
                           ReminderTaskRepository reminderRepository,
                           StoredMediaRepository storedMediaRepository,
                           ObjectMapper objectMapper,
                           @Value("${backup.dir:backup}") String backupDir,
                           @Value("${backup.retention-days:30}") int retentionDays,
                           @Value("${media.storage.root:stored-media}") String mediaRoot) {
        this.userProfileRepository = userProfileRepository;
        this.coreRepository = coreRepository;
        this.workRepository = workRepository;
        this.archiveRepository = archiveRepository;
        this.changeLogRepository = changeLogRepository;
        this.reminderRepository = reminderRepository;
        this.storedMediaRepository = storedMediaRepository;
        this.objectMapper = objectMapper;
        this.backupDir = Path.of(backupDir).toAbsolutePath().normalize();
        this.mediaRoot = Path.of(mediaRoot).toAbsolutePath().normalize();
        this.retentionDays = Math.max(1, retentionDays);
    }

    @Scheduled(cron = "${backup.cron:0 0 3 * * ?}")
    public void backupAll() {
        try {
            Path dayDir = checkedChild(backupDir, LocalDate.now().format(DAY));
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

    private void backupUser(Path dayDir, String userId) throws IOException {
        Path userDir = checkedChild(dayDir, "user-" + shortHash(userId));
        Files.createDirectories(userDir);
        List<StoredMedia> media = storedMediaRepository.findByUserIdOrderByCreatedAtAsc(userId);
        ObjectNode node = objectMapper.createObjectNode();
        node.put("userId", userId);
        node.set("profile", objectMapper.valueToTree(userProfileRepository.findById(userId).orElse(null)));
        node.set("coreMemories", objectMapper.valueToTree(coreRepository.findByUserIdOrderByCreatedAtAsc(userId)));
        node.set("workMemories", objectMapper.valueToTree(workRepository.findByUserIdAndArchivedFalse(userId)));
        node.set("archives", objectMapper.valueToTree(archiveRepository.findByUserIdOrderByCreatedAtDesc(userId)));
        node.set("changeLogs", objectMapper.valueToTree(
                changeLogRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 5000))));
        node.set("reminders", objectMapper.valueToTree(reminderRepository.findByUserIdAndStatus(userId, "PENDING")));
        node.set("storedMedia", objectMapper.valueToTree(media));
        node.set("mediaArtifacts", backupMedia(userDir, userId, media));
        writeAtomically(userDir.resolve("state.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node));
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
        LocalDate cutoff = LocalDate.now().minusDays(retentionDays);
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
}
