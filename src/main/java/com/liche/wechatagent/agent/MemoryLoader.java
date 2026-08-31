package com.liche.wechatagent.agent;

import com.liche.wechatagent.memory.UserCoreMemoryRepository;
import com.liche.wechatagent.memory.CoreMemoryService;
import com.liche.wechatagent.memory.UserWorkMemory;
import com.liche.wechatagent.memory.UserWorkMemoryRepository;
import com.liche.wechatagent.memory.WorkMemoryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 记忆加载规则（每次对话自动执行）：
 * 1. 固定置顶加载全部永久核心记忆，永远不会被截断；
 * 2. 中期工作记忆按「优先级 > 时间倒序」最多 15 条、总字符 ≤1500，超出截断老旧次要内容；
 * 3. 即时对话上下文由编排器拼接。
 */
@Component
public class MemoryLoader {

    public record LoadedMemory(String coreSection, String workSection) {
    }

    private final UserCoreMemoryRepository coreRepository;
    private final UserWorkMemoryRepository workRepository;
    private final int workMaxLoad;
    private final int workMaxChars;

    public MemoryLoader(UserCoreMemoryRepository coreRepository,
                        UserWorkMemoryRepository workRepository,
                        @Value("${memory.work-max-load:15}") int workMaxLoad,
                        @Value("${memory.work-max-chars:1500}") int workMaxChars) {
        this.coreRepository = coreRepository;
        this.workRepository = workRepository;
        this.workMaxLoad = workMaxLoad;
        this.workMaxChars = workMaxChars;
    }

    @Transactional
    public LoadedMemory load(String userId, String query) {
        LocalDateTime now = LocalDateTime.now();
        List<com.liche.wechatagent.memory.UserCoreMemory> cores = coreRepository.findByUserIdOrderByCreatedAtAsc(userId).stream()
                .filter(CoreMemoryService::isActive)
                .toList();
        String core = cores.stream()
                .map(m -> "- " + m.getContent())
                .collect(Collectors.joining("\n"));

        List<UserWorkMemory> active = new ArrayList<>(workRepository.findByUserIdAndArchivedFalse(userId).stream()
                .filter(memory -> WorkMemoryService.isActive(memory, now))
                .toList());
        active.sort(Comparator.comparingInt((UserWorkMemory memory) -> relevance(memory.getContent(), query)).reversed()
                .thenComparing(Comparator.comparing(UserWorkMemory::getPriority).reversed())
                .thenComparing(Comparator.comparing(UserWorkMemory::getUpdatedAt).reversed()));

        List<String> lines = new ArrayList<>();
        int total = 0;
        for (UserWorkMemory w : active) {
            if (lines.size() >= workMaxLoad) {
                break;
            }
            String line = "- " + w.getContent();
            if (total + line.length() > workMaxChars) {
                break;
            }
            lines.add(line);
            total += line.length();
            w.setLastUsedAt(now);
        }
        cores.forEach(memory -> memory.setLastUsedAt(now));
        if (!cores.isEmpty()) {
            coreRepository.saveAll(cores);
        }
        if (!lines.isEmpty()) {
            List<UserWorkMemory> selected = active.stream()
                    .filter(memory -> lines.contains("- " + memory.getContent()))
                    .toList();
            workRepository.saveAll(selected);
        }

        return new LoadedMemory(
                core.isBlank() ? "（暂无）" : core,
                lines.isEmpty() ? "（暂无）" : String.join("\n", lines));
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
