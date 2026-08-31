package com.liche.wechatagent.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.stream.Stream;

/** 每日自动全量备份记忆数据（核心/中期/归档/操作日志 + 人设与提醒），保留 N 天滚动 */
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
    private final ObjectMapper objectMapper;
    private final Path backupDir;
    private final int retentionDays;

    public MemoryBackupJob(UserProfileRepository userProfileRepository,
                           UserCoreMemoryRepository coreRepository,
                           UserWorkMemoryRepository workRepository,
                           MemoryArchiveRepository archiveRepository,
                           MemoryChangeLogRepository changeLogRepository,
                           ReminderTaskRepository reminderRepository,
                           ObjectMapper objectMapper,
                           @Value("${backup.dir:backup}") String backupDir,
                           @Value("${backup.retention-days:30}") int retentionDays) {
        this.userProfileRepository = userProfileRepository;
        this.coreRepository = coreRepository;
        this.workRepository = workRepository;
        this.archiveRepository = archiveRepository;
        this.changeLogRepository = changeLogRepository;
        this.reminderRepository = reminderRepository;
        this.objectMapper = objectMapper;
        this.backupDir = Path.of(backupDir);
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${backup.cron:0 0 3 * * ?}")
    public void backupAll() {
        try {
            Path dayDir = backupDir.resolve(LocalDate.now().format(DAY));
            Files.createDirectories(dayDir);
            for (var profile : userProfileRepository.findAll()) {
                String userId = profile.getUserId();
                ObjectNode node = objectMapper.createObjectNode();
                node.put("userId", userId);
                node.set("persona", objectMapper.valueToTree(profile));
                node.set("coreMemories", objectMapper.valueToTree(coreRepository.findByUserIdOrderByCreatedAtAsc(userId)));
                node.set("workMemories", objectMapper.valueToTree(workRepository.findByUserIdAndArchivedFalse(userId)));
                node.set("archives", objectMapper.valueToTree(archiveRepository.findByUserIdOrderByCreatedAtDesc(userId)));
                node.set("changeLogs", objectMapper.valueToTree(
                        changeLogRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 5000))));
                node.set("reminders", objectMapper.valueToTree(
                        reminderRepository.findByUserIdAndStatus(userId, "PENDING")));
                Files.writeString(dayDir.resolve("user_" + userId + ".json"),
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node));
            }
            log.info("每日记忆备份完成: {} ({} 个用户)", dayDir, userProfileRepository.count());
            prune();
        } catch (Exception e) {
            log.error("每日记忆备份失败", e);
        }
    }

    private void prune() throws IOException {
        if (!Files.isDirectory(backupDir)) {
            return;
        }
        LocalDate cutoff = LocalDate.now().minusDays(retentionDays);
        try (Stream<Path> dirs = Files.list(backupDir)) {
            dirs.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().matches("\\d{8}"))
                    .filter(p -> {
                        try {
                            return LocalDate.parse(p.getFileName().toString(), DAY).isBefore(cutoff);
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(dir -> {
                        try {
                            deleteRecursively(dir);
                            log.info("清理过期备份: {}", dir);
                        } catch (IOException e) {
                            log.warn("清理备份失败: {}", dir, e);
                        }
                    });
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("删除失败: {}", p, e);
                }
            });
        }
    }
}
