package com.liche.wechatagent.agent;

import com.liche.wechatagent.memory.UserCoreMemoryRepository;
import com.liche.wechatagent.memory.CoreMemoryService;
import com.liche.wechatagent.memory.UserWorkMemory;
import com.liche.wechatagent.memory.UserWorkMemoryRepository;
import com.liche.wechatagent.memory.WorkMemoryService;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final int workMaxLoad;
    private final int workMaxChars;
    private final int coreMaxLoad;
    private final int coreMaxChars;

    @Autowired
    public MemoryLoader(UserCoreMemoryRepository coreRepository,
                        UserWorkMemoryRepository workRepository,
                        @Value("${memory.work-max-load:15}") int workMaxLoad,
                        @Value("${memory.work-max-chars:1500}") int workMaxChars,
                        @Value("${memory.core-max-load:16}") int coreMaxLoad,
                        @Value("${memory.core-max-chars:2200}") int coreMaxChars) {
        this.coreRepository = coreRepository;
        this.workRepository = workRepository;
        this.workMaxLoad = Math.max(1, workMaxLoad);
        this.workMaxChars = Math.max(1, workMaxChars);
        this.coreMaxLoad = Math.max(1, coreMaxLoad);
        this.coreMaxChars = Math.max(1, coreMaxChars);
    }

    MemoryLoader(UserCoreMemoryRepository coreRepository,
                 UserWorkMemoryRepository workRepository,
                 int workMaxLoad,
                 int workMaxChars) {
        this(coreRepository, workRepository, workMaxLoad, workMaxChars, 16, 2200);
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
        List<String> coreLines = withinBudget(cores.stream().map(memory -> "- " + memory.getContent()).toList(),
                coreMaxLoad, coreMaxChars);

        List<UserWorkMemory> active = new ArrayList<>(workRepository.findByUserIdAndArchivedFalse(userId).stream()
                .filter(memory -> WorkMemoryService.isActive(memory, now))
                .toList());
        active.sort(Comparator.comparingInt((UserWorkMemory memory) -> relevance(memory.getContent(), query)).reversed()
                .thenComparing(Comparator.comparing(UserWorkMemory::getPriority).reversed())
                .thenComparing(Comparator.comparing(UserWorkMemory::getUpdatedAt).reversed()));

        List<String> lines = withinBudget(active.stream().map(memory -> "- " + memory.getContent()).toList(),
                workMaxLoad, workMaxChars);

        return new LoadedMemory(
                coreLines.isEmpty() ? "（暂无）" : String.join("\n", coreLines),
                lines.isEmpty() ? "（暂无）" : String.join("\n", lines));
    }

    private List<String> withinBudget(List<String> candidates, int maxItems, int maxChars) {
        List<String> selected = new ArrayList<>();
        int totalChars = 0;
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank() || selected.size() >= maxItems) {
                continue;
            }
            if (totalChars + candidate.length() > maxChars) {
                continue;
            }
            selected.add(candidate);
            totalChars += candidate.length();
        }
        return selected;
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
