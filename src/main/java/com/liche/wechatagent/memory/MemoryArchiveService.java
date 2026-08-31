package com.liche.wechatagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 记忆归档压缩：中期记忆超过阈值（默认 20 条）时，异步将最老旧的 N 条次要记忆
 * 合并为 1 条精简摘要；原始记忆标记 is_archived=1 不删除；原始 ID 与摘要存入归档表永久可回溯。
 */
@Service
public class MemoryArchiveService {

    private static final Logger log = LoggerFactory.getLogger(MemoryArchiveService.class);

    private final ChatModel chatModel;
    private final UserWorkMemoryRepository workRepository;
    private final MemoryArchiveRepository archiveRepository;
    private final WorkMemoryService workMemoryService;
    private final ObjectMapper objectMapper;
    private final int threshold;
    private final int batch;

    public MemoryArchiveService(ChatModel chatModel,
                                UserWorkMemoryRepository workRepository,
                                MemoryArchiveRepository archiveRepository,
                                WorkMemoryService workMemoryService,
                                ObjectMapper objectMapper,
                                @Value("${memory.work-archive-threshold:20}") int threshold,
                                @Value("${memory.work-archive-batch:10}") int batch) {
        this.chatModel = chatModel;
        this.workRepository = workRepository;
        this.archiveRepository = archiveRepository;
        this.workMemoryService = workMemoryService;
        this.objectMapper = objectMapper;
        this.threshold = threshold;
        this.batch = batch;
    }

    /** 写入后立即检查（由提取器调用） */
    public void compressIfNeeded(String userId) {
        long active = workMemoryService.countActive(userId);
        if (active <= threshold) {
            return;
        }
        List<UserWorkMemory> oldest = workRepository
                .findByUserIdAndArchivedFalseOrderByPriorityAscCreatedAtAsc(userId, PageRequest.of(0, batch * 3)).stream()
                .filter(memory -> WorkMemoryService.isActive(memory, java.time.LocalDateTime.now()))
                .filter(memory -> !"archive_summary".equalsIgnoreCase(memory.getSource()))
                .filter(memory -> memory.getValidUntil() == null)
                .limit(batch)
                .toList();
        if (oldest.isEmpty()) {
            return;
        }
        try {
            String summary = summarize(userId, oldest);
            List<Long> ids = oldest.stream().map(UserWorkMemory::getId).toList();
            MemoryArchive archive = archiveRepository.save(new MemoryArchive(
                    userId, summary, objectMapper.writeValueAsString(ids)));
            workMemoryService.markArchived(userId, ids, archive.getId());
            workMemoryService.add(userId, summary, 3, "archive_summary", "SYSTEM");
            log.info("记忆归档 user={} 归档{}条 -> 摘要1条, archiveId={}", userId, ids.size(), archive.getId());
        } catch (Exception e) {
            log.warn("记忆归档失败 user={}", userId, e);
        }
    }

    /** 周期兜底扫描（每 10 分钟），防止提取链路异常时长期不归档 */
    @Scheduled(fixedDelay = 600_000, initialDelay = 300_000)
    public void periodicSweep() {
        for (String userId : workRepository.findDistinctUserIds()) {
            compressIfNeeded(userId);
        }
    }

    private String summarize(String userId, List<UserWorkMemory> items) {
        StringBuilder sb = new StringBuilder("请把下面这 ").append(items.size())
                .append(" 条次要记忆合并成 1 条精简记录（不超过 100 字，保留关键时间、事项、偏好）：\n");
        for (UserWorkMemory item : items) {
            sb.append("- ").append(item.getContent()).append("\n");
        }
        sb.append("只输出合并后的记录本身，不要解释。");
        String summary = chatModel.chat(sb.toString()).trim();
        if (summary.length() > 1000) {
            summary = summary.substring(0, 1000);
        }
        return summary;
    }
}
