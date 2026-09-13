package com.liche.wechatagent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liche.wechatagent.config.LlmScenario;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 记忆**归纳**（consolidation）：把散落的零碎记忆提升成少量「更高层的稳定记忆」。
 *
 * <p>**为什么要有这一步**（2026-09-14 加，用户症状：「记的都是碎片，没有归纳」）：提取器只会往库里加条目——
 * 每条都忠实、但彼此孤立；用得越久，库里越是「一张张便签」，而不是「我认识你这个人」。业界两条主流做法都是这个思路：
 * OpenAI 的 ChatGPT 现在用后台 dreaming 跨对话自动归纳长期记忆并处理过时内容；斯坦福 Generative Agents 用
 * 「累计 importance 超过阈值就 reflection」，把零散观察归纳成高层结论（见 docs/memory-extraction.md）。
 * 这里做的是它们的轻量版：**每天一次、只在活跃核心记忆够多时才跑**。
 *
 * <p>**安全约束（不敢丢信息）**：
 * <ul>
 *   <li>提示词明确要求保留具体日期、分数、院校、科目、仍在进行中的事项，只做合并与提升，不做删除；</li>
 *   <li>模型只能通过「替换（replaces）」或「新增」两种方式落地，**没有删除通道**；被替换的旧条目走
 *       {@link CoreMemoryService#replaceFromExtraction}：旧行 status=SUPERSEDED 仍在库里、有变更日志可查；</li>
 *   <li>没有匹配上的老记忆**原样保留**，只有「同一件事的新旧两条」才由程序兜底合并（相似度 ≥ 0.85）；</li>
 *   <li>结果为空、解析失败、模型异常 → 一行都不写。</li>
 * </ul>
 */
@Component
public class MemoryConsolidationService {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidationService.class);

    /** 程序兜底合并的相似度阈值：同一件事的新旧两条（≥0.85 才合并，宁可不合） */
    private static final double DUPLICATE_SIMILARITY = 0.85d;
    /** 模型说「这条 replaces 掉了某某」，要把某条已有记忆认出来所需的相似度（宽松一点） */
    private static final double MATCH_SIMILARITY = 0.72d;

    private final ChatModel chatModel;
    private final CoreMemoryService coreMemoryService;
    private final WorkMemoryService workMemoryService;
    private final UserCoreMemoryRepository coreRepository;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final int minActiveCores;
    private final int maxOutput;
    private final int maxInputCores;
    private final int maxInputWork;

    public MemoryConsolidationService(ChatModel chatModel,
                                      CoreMemoryService coreMemoryService,
                                      WorkMemoryService workMemoryService,
                                      UserCoreMemoryRepository coreRepository,
                                      ObjectMapper objectMapper,
                                      @Value("${memory.consolidation.enabled:true}") boolean enabled,
                                      @Value("${memory.consolidation.min-active-cores:15}") int minActiveCores,
                                      @Value("${memory.consolidation.max-output:8}") int maxOutput,
                                      @Value("${memory.consolidation.max-input-cores:60}") int maxInputCores,
                                      @Value("${memory.consolidation.max-input-work:20}") int maxInputWork) {
        this.chatModel = chatModel;
        this.coreMemoryService = coreMemoryService;
        this.workMemoryService = workMemoryService;
        this.coreRepository = coreRepository;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.minActiveCores = Math.max(1, minActiveCores);
        this.maxOutput = Math.max(1, maxOutput);
        this.maxInputCores = Math.max(this.minActiveCores, maxInputCores);
        this.maxInputWork = Math.max(0, maxInputWork);
    }

    /** 每天凌晨跑一次（默认 04:10，可配）；只处理「活跃核心记忆 ≥ min-active-cores」的用户 */
    @Scheduled(cron = "${memory.consolidation-cron:0 10 4 * * ?}")
    public void dailySweep() {
        if (!enabled || chatModel == null) {
            return;
        }
        List<String> userIds = coreRepository.findDistinctUserIds();
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        for (String userId : userIds) {
            try {
                consolidate(userId);
            } catch (Exception e) {
                log.warn("记忆归纳失败 user={}", userId, e);
            }
        }
    }

    /** @return true 表示这次真的调用了模型并尝试写回 */
    public boolean consolidate(String userId) {
        if (!enabled || chatModel == null || userId == null || userId.isBlank()) {
            return false;
        }
        List<UserCoreMemory> cores = coreMemoryService.listActive(userId);
        if (cores.size() < minActiveCores) {
            log.info("记忆归纳跳过：活跃核心记忆只有 {} 条（阈值 {}）user={}", cores.size(), minActiveCores, userId);
            return false;
        }
        List<UserWorkMemory> works = workMemoryService == null ? List.of() : workMemoryService.listActive(userId);
        String prompt = buildPrompt(cores, works);
        String response = LlmScenario.run(LlmScenario.CONSOLIDATE, () -> chatModel.chat(prompt));
        List<ConsolidatedMemory> memories = parse(response);
        if (memories.isEmpty()) {
            log.info("记忆归纳没有产出（保持原样）user={} 输入核心={} 输入中期={}", userId, cores.size(), works.size());
            return false;
        }
        int added = 0;
        int replaced = 0;
        for (ConsolidatedMemory memory : memories) {
            if (memory.content() == null || memory.content().isBlank() || memory.content().length() > 400) {
                continue;
            }
            UserCoreMemory target = matchTarget(cores, memory.replaces());
            MemoryProvenance provenance = MemoryProvenance.automatic("consolidation");
            MemoryAttributes attributes = new MemoryAttributes(memory.importance(), memory.confidence(),
                    memory.keywords());
            if (target == null) {
                coreMemoryService.add(userId, memory.content(), "AUTO", provenance, attributes);
                added++;
            } else {
                coreMemoryService.replaceFromExtraction(userId, target.getId(), memory.content(),
                        "记忆归纳：把同类零碎记忆合并成更高层的稳定记忆", "AUTO", provenance, attributes);
                replaced++;
            }
        }
        int merged = mergeNearDuplicateCores(userId);
        log.info("记忆归纳完成 user={} 输入核心={} 输入中期={} 新增高层={} 替换旧条目={} 程序兜底合并={}",
                userId, cores.size(), works.size(), added, replaced, merged);
        return true;
    }

    /** 模型给的 replaces 命中的那条已有记忆；一条都认不出来就返回 null（=当成新增） */
    private UserCoreMemory matchTarget(List<UserCoreMemory> cores, List<String> replaces) {
        if (replaces == null || replaces.isEmpty()) {
            return null;
        }
        for (String old : replaces) {
            if (old == null || old.isBlank()) {
                continue;
            }
            for (UserCoreMemory core : cores) {
                if (core == null || core.getContent() == null) {
                    continue;
                }
                if (core.getContent().contains(old) || old.contains(core.getContent())
                        || MemoryTextSimilarity.similarity(core.getContent(), old) >= MATCH_SIMILARITY) {
                    return core;
                }
            }
        }
        return null;
    }

    /**
     * 程序兜底：归纳完之后，活跃核心记忆里如果还有「同一件事的新旧两条」（相似度 ≥ 0.85），保留更新的那条，
     * 旧的置 SUPERSEDED（**不删行**）。
     */
    private int mergeNearDuplicateCores(String userId) {
        List<UserCoreMemory> cores = new ArrayList<>(coreMemoryService.listActive(userId));
        int merged = 0;
        for (int i = 0; i < cores.size(); i++) {
            UserCoreMemory newer = cores.get(i);
            if (newer == null || newer.getContent() == null || !CoreMemoryService.isActive(newer)) {
                continue;
            }
            for (int j = i + 1; j < cores.size(); j++) {
                UserCoreMemory older = cores.get(j);
                if (older == null || older.getContent() == null || !CoreMemoryService.isActive(older)) {
                    continue;
                }
                double score = MemoryTextSimilarity.similarity(newer.getContent(), older.getContent());
                if (score < DUPLICATE_SIMILARITY) {
                    continue;
                }
                try {
                    coreMemoryService.replaceFromExtraction(userId, older.getId(), newer.getContent(),
                            "记忆归纳：与更新的同类记忆重复，合并到新条目", "AUTO",
                            MemoryProvenance.automatic("consolidation"),
                            new MemoryAttributes(newer.getImportance(), newer.getConfidence(), List.of()));
                    merged++;
                    log.info("记忆归纳合并重复条目 user={} 保留 id={} 取代 id={} similarity={}",
                            userId, newer.getId(), older.getId(), String.format(Locale.ROOT, "%.2f", score));
                } catch (Exception e) {
                    log.warn("记忆归纳合并失败 user={} id={}", userId, older.getId(), e);
                }
            }
        }
        return merged;
    }

    private List<ConsolidatedMemory> parse(String response) {
        if (response == null || response.isBlank()) {
            return List.of();
        }
        String text = response.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            log.warn("记忆归纳输出不是 JSON，忽略");
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(text.substring(start, end + 1));
            List<ConsolidatedMemory> result = new ArrayList<>();
            for (JsonNode node : root.path("memories")) {
                String content = node.path("content").asText("").trim();
                if (content.isEmpty()) {
                    continue;
                }
                List<String> replaces = new ArrayList<>();
                for (JsonNode old : node.path("replaces")) {
                    if (old.isTextual() && !old.asText().isBlank()) {
                        replaces.add(old.asText().trim());
                    }
                }
                List<String> keywords = new ArrayList<>();
                for (JsonNode keyword : node.path("keywords")) {
                    if (keyword.isTextual() && !keyword.asText().isBlank()) {
                        keywords.add(keyword.asText().trim());
                    }
                }
                result.add(new ConsolidatedMemory(content, node.path("importance").asInt(4),
                        node.path("confidence").asInt(90), keywords, replaces));
                if (result.size() >= maxOutput) {
                    break;
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("记忆归纳输出解析失败，忽略这一轮", e);
            return List.of();
        }
    }

    private String buildPrompt(List<UserCoreMemory> cores, List<UserWorkMemory> works) {
        StringBuilder sb = new StringBuilder();
        sb.append("你在帮一个长期陪伴型 Agent 整理它对用户的记忆。任务：把下面这些零碎记忆归纳成不超过 ")
                .append(maxOutput).append(" 条更高层的稳定记忆（用户是谁、长期目标、长期偏好、原则底线、当前主线）。\n\n");
        sb.append("硬要求：\n")
                .append("1. 不许丢信息：具体日期、分数、院校、科目、金额、人名这类硬信息必须原样保留在归纳后的条目里。\n")
                .append("2. 正在进行的项目/任务不要并掉：各自的截止时间和当前状态都要留着（宁可多留一条）。\n")
                .append("3. 只做合并同类项和提升抽象层级，例如「数学目标 130」+「想冲 140」合成一条「考研目标分：数学冲 140（原目标 130）」；\n")
                .append("   但「不喜欢咖啡」和「要交开题报告」属于不同主题，不要硬凑成一条。\n")
                .append("4. 归纳后每条不超过 80 字；条数越少越好，但不能少于实际的主题数。\n")
                .append("5. replaces 里逐字抄写被这条归纳取代的原始记忆全文（必须能在下面列表里找到）；\n")
                .append("   没有被取代的、或拿不准的条目一律放进 keep，程序不会动它们。\n")
                .append("6. 不要凭推测改写内容：拿不准就留原文。\n\n");
        int coreLimit = Math.min(cores.size(), maxInputCores);
        sb.append("已有的核心记忆（共 ").append(cores.size()).append(" 条，列出最近 ").append(coreLimit).append(" 条）：\n");
        for (int i = 0; i < coreLimit; i++) {
            UserCoreMemory core = cores.get(i);
            sb.append("- ").append(core.getContent()).append('\n');
        }
        int workLimit = Math.min(works.size(), maxInputWork);
        if (workLimit > 0) {
            sb.append("\n进行中的中期记忆（共 ").append(works.size()).append(" 条，列出 ").append(workLimit).append(" 条）：\n");
            for (int i = 0; i < workLimit; i++) {
                UserWorkMemory work = works.get(i);
                sb.append("- ").append(work.getContent());
                if (work.getValidUntil() != null) {
                    sb.append("（截止 ").append(work.getValidUntil().toLocalDate()).append('）');
                }
                sb.append('\n');
            }
        }
        sb.append("\n只输出 JSON，不要解释：\n")
                .append("{\"memories\":[{\"content\":\"归纳后的记忆\",\"importance\":4,\"confidence\":90,")
                .append("\"keywords\":[\"关键词\"],\"replaces\":[\"被取代的原始记忆全文\"]}],")
                .append("\"keep\":[\"不需要改动、也没被取代的原始记忆全文\"]}");
        return sb.toString();
    }

    record ConsolidatedMemory(String content, int importance, int confidence, List<String> keywords,
                              List<String> replaces) {
    }
}
