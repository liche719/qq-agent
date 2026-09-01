package com.liche.wechatagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liche.wechatagent.config.MemoryPolicyProperties;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.time.ZoneId;
import java.time.LocalDateTime;

/**
 * 记忆归档压缩：中期记忆超过阈值（默认 20 条）时，异步将最老旧的 N 条次要记忆
 * 合并为 1 条精简摘要；原始记忆标记 is_archived=1 不删除；原始 ID 与摘要存入归档表永久可回溯。
 */
@Service
public class MemoryArchiveService {

    private static final Logger log = LoggerFactory.getLogger(MemoryArchiveService.class);
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    private final ChatModel chatModel;
    private final UserWorkMemoryRepository workRepository;
    private final MemoryArchiveRepository archiveRepository;
    private final WorkMemoryService workMemoryService;
    private final ObjectMapper objectMapper;
    private final MemoryChangeLogRepository changeLogRepository;
    private final MemoryMutationLock mutationLock;
    private final int threshold;
    private final int batch;
    private final ZoneId zone;
    private final int summaryTargetChars;
    private final int summaryMaxChars;
    private final int summaryConfidence;
    private final int defaultImportance;
    private final int defaultPriority;

    @Autowired
    public MemoryArchiveService(ChatModel chatModel,
                                UserWorkMemoryRepository workRepository,
                                MemoryArchiveRepository archiveRepository,
                                WorkMemoryService workMemoryService,
                                ObjectMapper objectMapper,
                                MemoryChangeLogRepository changeLogRepository,
                                 MemoryMutationLock mutationLock,
                                 MemoryPolicyProperties policyProperties,
                                 @Value("${memory.work-archive-threshold:20}") int threshold,
                                @Value("${memory.work-archive-batch:10}") int batch,
                                @Value("${app.time-zone:Asia/Shanghai}") String timeZoneId) {
        this.chatModel = chatModel;
        this.workRepository = workRepository;
        this.archiveRepository = archiveRepository;
        this.workMemoryService = workMemoryService;
        this.objectMapper = objectMapper;
        this.changeLogRepository = changeLogRepository;
        this.mutationLock = mutationLock;
        this.threshold = Math.max(1, threshold);
        this.batch = Math.max(1, batch);
        this.zone = parseZone(timeZoneId);
        MemoryPolicyProperties policies = policyProperties == null ? new MemoryPolicyProperties() : policyProperties;
        this.summaryTargetChars = bounded(policies.getArchiveSummaryTargetChars(), 16, 4_000,
                MemoryPolicyProperties.DEFAULT_ARCHIVE_SUMMARY_TARGET_CHARS);
        this.summaryMaxChars = bounded(policies.getArchiveSummaryMaxChars(), summaryTargetChars, 20_000,
                MemoryPolicyProperties.DEFAULT_ARCHIVE_SUMMARY_MAX_CHARS);
        this.summaryConfidence = bounded(policies.getArchiveSummaryConfidence(), 0, 100,
                MemoryPolicyProperties.DEFAULT_ARCHIVE_SUMMARY_CONFIDENCE);
        this.defaultImportance = bounded(policies.getDefaultWorkImportance(), 1, 5,
                MemoryPolicyProperties.DEFAULT_WORK_IMPORTANCE);
        this.defaultPriority = bounded(policies.getDefaultWorkPriority(), 1, 5,
                MemoryPolicyProperties.DEFAULT_WORK_PRIORITY);
    }

    public MemoryArchiveService(ChatModel chatModel,
                                UserWorkMemoryRepository workRepository,
                                MemoryArchiveRepository archiveRepository,
                                WorkMemoryService workMemoryService,
                                ObjectMapper objectMapper,
                                MemoryChangeLogRepository changeLogRepository,
                                MemoryMutationLock mutationLock,
                                int threshold,
                                int batch) {
        this(chatModel, workRepository, archiveRepository, workMemoryService, objectMapper,
                changeLogRepository, mutationLock, new MemoryPolicyProperties(), threshold, batch, DEFAULT_ZONE.getId());
    }

    /** Compatibility constructor for embedders that supplied sweep values before scheduling became property-driven. */
    public MemoryArchiveService(ChatModel chatModel,
                                UserWorkMemoryRepository workRepository,
                                MemoryArchiveRepository archiveRepository,
                                WorkMemoryService workMemoryService,
                                ObjectMapper objectMapper,
                                MemoryChangeLogRepository changeLogRepository,
                                MemoryMutationLock mutationLock,
                                int threshold,
                                int batch,
                                long ignoredSweepIntervalMillis,
                                long ignoredSweepInitialDelayMillis) {
        this(chatModel, workRepository, archiveRepository, workMemoryService, objectMapper,
                changeLogRepository, mutationLock, new MemoryPolicyProperties(), threshold, batch, DEFAULT_ZONE.getId());
    }

    /** 写入后立即检查（由提取器调用） */
    public void compressIfNeeded(String userId) {
        mutationLock.runExclusive(userId, () -> compressIfNeededLocked(userId));
    }

    private void compressIfNeededLocked(String userId) {
        if (!validUserId(userId)) {
            return;
        }
        long active = workMemoryService.countActive(userId);
        if (active <= threshold) {
            return;
        }
        List<UserWorkMemory> candidates = workRepository
                .findByUserIdAndArchivedFalseOrderByPriorityAscCreatedAtAsc(userId, PageRequest.of(0, batch * 3));
        List<UserWorkMemory> oldest = (candidates == null ? List.<UserWorkMemory>of() : candidates).stream()
                .filter(memory -> memory != null && userId.equals(memory.getUserId()))
                .filter(memory -> WorkMemoryService.isActive(memory, now()))
                .filter(memory -> !"archive_summary".equalsIgnoreCase(memory.getSource()))
                .filter(memory -> memory.getValidUntil() == null)
                .limit(batch)
                .toList();
        if (oldest.isEmpty()) {
            return;
        }
        try {
            String summary = summarize(userId, oldest);
            if (summary == null || summary.isBlank()) {
                log.warn("记忆归档未生成有效摘要 user={}，保留原始记忆", userId);
                return;
            }
            List<Long> ids = oldest.stream().map(UserWorkMemory::getId).toList();
            MemoryArchive archive = archiveRepository.save(new MemoryArchive(
                    userId, summary, objectMapper.writeValueAsString(ids)));
            if (archive == null) {
                log.warn("记忆归档记录保存为空 user={}，保留原始记忆", userId);
                return;
            }
            workMemoryService.markArchived(userId, ids, archive.getId());
            workMemoryService.add(userId, summary, summaryPriority(oldest), "archive_summary", "SYSTEM",
                    archiveProvenance(oldest), null, archiveAttributes(oldest));
            log.info("记忆归档 user={} 归档{}条 -> 摘要1条, archiveId={}", userId, ids.size(), archive.getId());
        } catch (Exception e) {
            log.warn("记忆归档失败 user={}", userId, e);
        }
    }

    /** 删除归档摘要时，连同该摘要隐藏的原始工作记忆一起遗忘。 */
    @Transactional
    public List<ForgottenMemory> forgetSummary(String userId, String summary) {
        if (!validUserId(userId) || summary == null || summary.isBlank()) {
            return List.of();
        }
        return mutationLock.callExclusive(userId, () -> {
            List<ForgottenMemory> removed = new ArrayList<>();
            List<MemoryArchive> archives = archiveRepository.findByUserIdOrderByCreatedAtDesc(userId);
            if (archives == null) {
                return removed;
            }
            Set<Long> removedOriginalIds = new LinkedHashSet<>();
            for (MemoryArchive archive : archives) {
                if (archive == null || !userId.equals(archive.getUserId())) {
                    continue;
                }
                if (!sameSummary(archive.getSummary(), summary)) {
                    continue;
                }
                for (Long originalId : originalIds(archive.getOriginalIds())) {
                    if (!removedOriginalIds.add(originalId)) {
                        continue;
                    }
                    workRepository.findById(originalId)
                            .filter(memory -> userId.equals(memory.getUserId()))
                            .ifPresent(memory -> removed.add(workMemoryService.forget(userId, memory.getId())));
                }
                removeArchive(userId, archive, removed);
            }
            return removed;
        });
    }

    /** Removes an archived work item from any active archive summary that could otherwise reintroduce it. */
    @Transactional
    public List<ForgottenMemory> forgetSourceMemory(String userId, Long sourceMemoryId) {
        if (!validUserId(userId) || sourceMemoryId == null) {
            return List.of();
        }
        return mutationLock.callExclusive(userId, () -> {
            List<ForgottenMemory> removed = new ArrayList<>();
            List<MemoryArchive> archives = archiveRepository.findByUserIdOrderByCreatedAtDesc(userId);
            if (archives == null) {
                return removed;
            }
            for (MemoryArchive archive : archives) {
                if (archive == null || !userId.equals(archive.getUserId())) {
                    continue;
                }
                if (!originalIds(archive.getOriginalIds()).contains(sourceMemoryId)) {
                    continue;
                }
                List<UserWorkMemory> active = workRepository.findByUserIdAndArchivedFalse(userId);
                (active == null ? List.<UserWorkMemory>of() : active).stream()
                        .filter(memory -> memory != null && userId.equals(memory.getUserId()))
                        .filter(memory -> "archive_summary".equalsIgnoreCase(memory.getSource()))
                        .filter(memory -> sameSummary(memory.getContent(), archive.getSummary()))
                        .forEach(memory -> removed.add(workMemoryService.forget(userId, memory.getId())));
                removeArchive(userId, archive, removed);
            }
            return removed;
        });
    }

    /** 周期兜底扫描（每 10 分钟），防止提取链路异常时长期不归档 */
    @Scheduled(fixedDelayString = "${memory.archive-sweep-interval-ms:600000}",
            initialDelayString = "${memory.archive-sweep-initial-delay-ms:300000}")
    public void periodicSweep() {
        List<String> userIds = workRepository.findDistinctUserIds();
        if (userIds == null) {
            return;
        }
        for (String userId : userIds) {
            compressIfNeeded(userId);
        }
    }

    private String summarize(String userId, List<UserWorkMemory> items) {
        StringBuilder sb = new StringBuilder("请把下面这 ").append(items.size())
                .append(" 条次要记忆合并成 1 条精简记录（不超过 ").append(summaryTargetChars)
                .append(" 字，保留关键时间、事项、偏好）：\n");
        for (UserWorkMemory item : items) {
            sb.append("- ").append(item.getContent()).append("\n");
        }
        sb.append("只输出合并后的记录本身，不要解释。");
        if (chatModel == null) {
            return "";
        }
        String response = chatModel.chat(sb.toString());
        String summary = response == null ? "" : response.trim();
        if (summary.length() > summaryMaxChars) {
            summary = summary.substring(0, summaryMaxChars);
        }
        return summary;
    }

    private MemoryProvenance archiveProvenance(List<UserWorkMemory> items) {
        Set<String> messageIds = new LinkedHashSet<>();
        Set<Long> mediaIds = new LinkedHashSet<>();
        for (UserWorkMemory item : items) {
            MemoryProvenance provenance = MemoryProvenance.fromStored(item.getSourceType(), item.getConfidence(),
                    item.getSourceMessageIds(), item.getSourceMediaIds());
            messageIds.addAll(provenance.sourceMessageIds());
            mediaIds.addAll(provenance.sourceMediaIds());
        }
        return new MemoryProvenance("SYSTEM_SUMMARY", summaryConfidence, List.copyOf(messageIds), List.copyOf(mediaIds));
    }

    private MemoryAttributes archiveAttributes(List<UserWorkMemory> items) {
        int importance = items.stream().map(UserWorkMemory::getImportance).filter(value -> value != null)
                .mapToInt(Integer::intValue).max().orElse(defaultImportance);
        int confidence = items.stream().map(UserWorkMemory::getConfidence).filter(value -> value != null)
                .mapToInt(Integer::intValue).min().orElse(70);
        Set<String> keywords = new LinkedHashSet<>();
        for (UserWorkMemory item : items) {
            keywords.addAll(MemoryAttributes.fromStored(item.getImportance(), item.getConfidence(),
                    item.getKeywords()).keywords());
        }
        return new MemoryAttributes(importance, Math.min(summaryConfidence, confidence), List.copyOf(keywords));
    }

    private int summaryPriority(List<UserWorkMemory> items) {
        return items.stream().map(UserWorkMemory::getPriority).filter(value -> value != null)
                .mapToInt(Integer::intValue).max().orElse(defaultPriority);
    }

    private boolean sameSummary(String first, String second) {
        return first != null && second != null && first.trim().equals(second.trim());
    }

    private void removeArchive(String userId, MemoryArchive archive, List<ForgottenMemory> removed) {
        archiveRepository.delete(archive);
        changeLogRepository.redactContentForMemory(userId, "ARCHIVE", archive.getId());
        changeLogRepository.save(new MemoryChangeLog(userId, "FORGET", "ARCHIVE", archive.getId(), null, null,
                "用户删除归档摘要；审计正文已清除", "USER"));
        removed.add(new ForgottenMemory("ARCHIVE", archive.getId(), archive.getSummary(), "", List.of()));
    }

    private List<Long> originalIds(String serializedIds) {
        List<Long> result = new ArrayList<>();
        try {
            for (var node : objectMapper.readTree(serializedIds == null ? "[]" : serializedIds)) {
                if (node.canConvertToLong()) {
                    long id = node.asLong();
                    if (id > 0) {
                        result.add(id);
                    }
                }
            }
        } catch (Exception exception) {
            log.warn("归档原始记忆编号解析失败，跳过级联遗忘 reason={}", exception.getClass().getSimpleName());
        }
        return result;
    }

    private boolean validUserId(String userId) {
        return userId != null && !userId.isBlank();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(zone);
    }

    private ZoneId parseZone(String value) {
        try {
            return ZoneId.of(value);
        } catch (RuntimeException ignored) {
            return DEFAULT_ZONE;
        }
    }

    private int bounded(int value, int minimum, int maximum, int fallback) {
        return value < minimum || value > maximum ? fallback : value;
    }
}
