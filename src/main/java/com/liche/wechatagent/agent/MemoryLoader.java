package com.liche.wechatagent.agent;

import com.liche.wechatagent.memory.UserCoreMemoryRepository;
import com.liche.wechatagent.memory.CoreMemoryService;
import com.liche.wechatagent.memory.MemoryProvenance;
import com.liche.wechatagent.memory.UserWorkMemory;
import com.liche.wechatagent.memory.UserWorkMemoryRepository;
import com.liche.wechatagent.memory.WorkMemoryService;
import com.liche.wechatagent.media.StoredMedia;
import com.liche.wechatagent.media.StoredMediaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 记忆加载规则（每次对话自动执行）：
 * 1. 核心记忆按相关性和最近确认时间在固定预算内加载；
 * 2. 中期工作记忆按「优先级 > 时间倒序」最多 15 条、总字符 ≤1500，超出截断老旧次要内容；
 * 3. 即时对话上下文由编排器拼接。
 */
@Component
public class MemoryLoader {

    public record LoadedMemory(String coreSection, String workSection) {
    }

    private final UserCoreMemoryRepository coreRepository;
    private final UserWorkMemoryRepository workRepository;
    private final StoredMediaRepository storedMediaRepository;
    private final int workMaxLoad;
    private final int workMaxChars;
    private final int coreMaxLoad;
    private final int coreMaxChars;
    private final int usageTouchIntervalMinutes;

    @Autowired
    public MemoryLoader(UserCoreMemoryRepository coreRepository,
                        UserWorkMemoryRepository workRepository,
                        StoredMediaRepository storedMediaRepository,
                        @Value("${memory.work-max-load:15}") int workMaxLoad,
                        @Value("${memory.work-max-chars:1500}") int workMaxChars,
                        @Value("${memory.core-max-load:16}") int coreMaxLoad,
                        @Value("${memory.core-max-chars:2200}") int coreMaxChars,
                        @Value("${memory.usage-touch-interval-minutes:15}") int usageTouchIntervalMinutes) {
        this.coreRepository = coreRepository;
        this.workRepository = workRepository;
        this.storedMediaRepository = storedMediaRepository;
        this.workMaxLoad = Math.max(1, workMaxLoad);
        this.workMaxChars = Math.max(1, workMaxChars);
        this.coreMaxLoad = Math.max(1, coreMaxLoad);
        this.coreMaxChars = Math.max(1, coreMaxChars);
        this.usageTouchIntervalMinutes = Math.max(1, usageTouchIntervalMinutes);
    }

    MemoryLoader(UserCoreMemoryRepository coreRepository,
                 UserWorkMemoryRepository workRepository,
                 int workMaxLoad,
                 int workMaxChars) {
        this(coreRepository, workRepository, null, workMaxLoad, workMaxChars, 16, 2200, 15);
    }

    @Transactional
    public LoadedMemory load(String userId, String query) {
        LocalDateTime now = LocalDateTime.now();
        List<com.liche.wechatagent.memory.UserCoreMemory> cores = new ArrayList<>(coreRepository.findByUserIdOrderByCreatedAtAsc(userId).stream()
                .filter(CoreMemoryService::isActive)
                .toList());
        cores.sort(Comparator.comparingInt((com.liche.wechatagent.memory.UserCoreMemory memory) ->
                        relevance(memory.getContent(), query)).reversed()
                .thenComparing(com.liche.wechatagent.memory.UserCoreMemory::getLastConfirmedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(com.liche.wechatagent.memory.UserCoreMemory::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())));
        List<com.liche.wechatagent.memory.UserCoreMemory> coreCandidates = withinBudget(cores,
                com.liche.wechatagent.memory.UserCoreMemory::getContent, coreMaxLoad, coreMaxChars);

        List<UserWorkMemory> active = new ArrayList<>(workRepository.findByUserIdAndArchivedFalse(userId).stream()
                .filter(memory -> WorkMemoryService.isActive(memory, now))
                .toList());
        active.sort(Comparator.comparingInt((UserWorkMemory memory) -> relevance(memory.getContent(), query)).reversed()
                .thenComparing(Comparator.comparing(UserWorkMemory::getPriority).reversed())
                .thenComparing(Comparator.comparing(UserWorkMemory::getUpdatedAt).reversed()));

        List<UserWorkMemory> workCandidates = withinBudget(active, UserWorkMemory::getContent, workMaxLoad, workMaxChars);
        Map<Long, StoredMedia> linkedMedia = linkedMedia(userId, coreCandidates, workCandidates);
        List<com.liche.wechatagent.memory.UserCoreMemory> selectedCores = withinBudget(coreCandidates,
                memory -> memory.getContent() + mediaSuffix(memory.getSourceMediaIds(), linkedMedia),
                coreMaxLoad, coreMaxChars);
        List<UserWorkMemory> selectedWork = withinBudget(workCandidates,
                memory -> memory.getContent() + mediaSuffix(memory.getSourceMediaIds(), linkedMedia),
                workMaxLoad, workMaxChars);
        touchUsage(now, selectedCores, selectedWork);

        List<String> coreLines = selectedCores.stream()
                .map(memory -> "- " + memory.getContent() + mediaSuffix(memory.getSourceMediaIds(), linkedMedia))
                .toList();
        List<String> lines = selectedWork.stream()
                .map(memory -> "- " + memory.getContent() + mediaSuffix(memory.getSourceMediaIds(), linkedMedia))
                .toList();

        return new LoadedMemory(
                coreLines.isEmpty() ? "（暂无）" : String.join("\n", coreLines),
                lines.isEmpty() ? "（暂无）" : String.join("\n", lines));
    }

    private <T> List<T> withinBudget(List<T> candidates, Function<T, String> content,
                                     int maxItems, int maxChars) {
        List<T> selected = new ArrayList<>();
        int totalChars = 0;
        for (T candidate : candidates) {
            String text = content.apply(candidate);
            if (text == null || text.isBlank() || selected.size() >= maxItems) {
                continue;
            }
            if (totalChars + text.length() > maxChars) {
                continue;
            }
            selected.add(candidate);
            totalChars += text.length();
        }
        return selected;
    }

    private Map<Long, StoredMedia> linkedMedia(String userId,
                                                List<com.liche.wechatagent.memory.UserCoreMemory> cores,
                                                List<UserWorkMemory> work) {
        if (storedMediaRepository == null) {
            return Map.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        cores.forEach(memory -> ids.addAll(mediaIds(memory.getSourceMediaIds())));
        work.forEach(memory -> ids.addAll(mediaIds(memory.getSourceMediaIds())));
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, StoredMedia> result = new HashMap<>();
        storedMediaRepository.findByUserIdAndIdInAndStatus(userId, List.copyOf(ids), StoredMedia.ACTIVE)
                .forEach(media -> result.put(media.getId(), media));
        return result;
    }

    private String mediaSuffix(String sourceMediaIds, Map<Long, StoredMedia> mediaById) {
        List<String> labels = mediaIds(sourceMediaIds).stream()
                .map(mediaById::get)
                .filter(media -> media != null)
                .limit(3)
                .map(this::mediaLabel)
                .toList();
        return labels.isEmpty() ? "" : "（关联资料：" + String.join("；", labels) + "）";
    }

    private String mediaLabel(StoredMedia media) {
        String summary = media.getSummary() == null ? "" : media.getSummary().trim();
        if (summary.length() > 80) {
            summary = summary.substring(0, 80) + "…";
        }
        return "#" + media.getId() + " " + media.getFileName()
                + (summary.isBlank() ? "" : "：" + summary);
    }

    private List<Long> mediaIds(String sourceMediaIds) {
        return MemoryProvenance.fromStored("USER_EXPLICIT", 100, "", sourceMediaIds).sourceMediaIds();
    }

    private void touchUsage(LocalDateTime now,
                            List<com.liche.wechatagent.memory.UserCoreMemory> cores,
                            List<UserWorkMemory> work) {
        LocalDateTime refreshBefore = now.minusMinutes(usageTouchIntervalMinutes);
        List<com.liche.wechatagent.memory.UserCoreMemory> coreUpdates = cores.stream()
                .filter(memory -> memory.getLastUsedAt() == null || memory.getLastUsedAt().isBefore(refreshBefore))
                .peek(memory -> memory.setLastUsedAt(now))
                .toList();
        if (!coreUpdates.isEmpty()) {
            coreRepository.saveAll(coreUpdates);
        }
        List<UserWorkMemory> workUpdates = work.stream()
                .filter(memory -> memory.getLastUsedAt() == null || memory.getLastUsedAt().isBefore(refreshBefore))
                .peek(memory -> memory.setLastUsedAt(now))
                .toList();
        if (!workUpdates.isEmpty()) {
            workRepository.saveAll(workUpdates);
        }
    }

    private int relevance(String content, String query) {
        if (content == null || query == null || query.isBlank()) {
            return 0;
        }
        String lowerContent = content.toLowerCase();
        int score = 0;
        for (String token : query.toLowerCase().split("[^\\p{IsHan}a-z0-9]+")) {
            if (token.length() >= 2 && lowerContent.contains(token)) {
                score += Math.min(token.length(), 6);
            }
        }
        for (int index = 0; index + 1 < query.length(); index++) {
            String fragment = query.substring(index, index + 2);
            if (fragment.matches("[\\p{IsHan}]{2}") && lowerContent.contains(fragment)) {
                score += 1;
            }
        }
        return score;
    }
}
