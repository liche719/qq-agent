package com.liche.wechatagent.memory;

import com.liche.wechatagent.tool.AgentToolProvider;
import com.liche.wechatagent.tool.ToolBusinessResult;
import com.liche.wechatagent.tool.ToolExecutionClass;
import com.liche.wechatagent.tool.ToolExecutionPolicy;
import com.liche.wechatagent.tool.ToolStatusService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事实层的**召回工具**（2026-09-18；原来是每轮都往提示词里塞一段，按用户要求改成工具）。
 *
 * <p>为什么改成工具：提示词里每轮塞一段是"不管用不用都付一遍 token 和注意力"；
 * 而"会变的信息"（课表/教室/上课时间/老师/临时安排）**只在少数轮次才需要**。
 * 现在是模型自己判断"这轮要用"再来查——查一次的成本只有一次向量检索。
 *
 * <p>代价是**模型得知道有这个工具**：所以工具描述写得比较啰嗦，提示词第 26 条也写了"回答这类问题先查"。
 */
@Component
public class MemoryFactTool implements AgentToolProvider {

    /** 卡片的正文上限（一次问答给模型看的量） */
    private static final int CARD_CHARS = 1200;
    private static final int PER_FACT_CHARS = 80;

    private final MemoryFactService factService;
    private final MemoryExtractionRunRepository runs;
    private final ToolStatusService statusService;
    private final int recallLimit;
    private final double minScore;

    public MemoryFactTool(MemoryFactService factService,
                          MemoryExtractionRunRepository runs,
                          ToolStatusService statusService,
                          @Value("${memory.fact-tool-recall-limit:8}") int recallLimit,
                          @Value("${memory.fact-tool-min-score:0.3}") double minScore) {
        this.factService = factService;
        this.runs = runs;
        this.statusService = statusService;
        this.recallLimit = Math.max(1, Math.min(20, recallLimit));
        this.minScore = Math.max(0d, Math.min(0.99d, minScore));
    }

    @Tool(value = "查「会变的信息」的当前值：课表/教室/上课时间/老师/临时日程/目标分这类**用户说过、后来又可能改过**的事。"
            + "query 传你想查的那件事，用用户的原话最好（例如「第一周周二晚数学课教室」「英语课在哪上」「数学目标分」）。"
            + "**回答这类问题之前先查这里**，不要凭印象、更不要拿更早对话里的旧值；查不到就照实说没记过，不要编。"
            + "返回里「来源=图片/文件（基线）」是用户当初发的图片上的原值，可能已经被他后来的话取代，"
            + "以「补丁」（他自己说过的当前值）为准。")
    @ToolExecutionPolicy(value = ToolExecutionClass.FAST, allowParallel = true)
    public ToolBusinessResult recallMemoryFacts(String query) {
        String userId = requireCurrentUser();
        List<MemoryFact> hits = factService.recallFacts(userId, query, recallLimit, minScore);
        if (hits.isEmpty()) {
            // 查不到不是错误：给一句实话 + 最近记过的几条（有些是用户没提到但相关的）
            List<MemoryFact> recent = factService.activeFacts(userId, 5);
            if (recent.isEmpty()) {
                return ToolBusinessResult.success("没记过和「" + safe(query) + "」相关的事，也没记过别的会变的信息。"
                        + "（用户说过这类信息时系统会自动记下来，所以这里空着就是真的还没有。）");
            }
            return ToolBusinessResult.success("没找到和「" + safe(query) + "」直接相关的事实。"
                    + "最近记过的会变信息有这些，看有没有相关：\n" + render(recent));
        }
        return ToolBusinessResult.success(render(hits));
    }

    @Tool(value = "看最近几次「记忆提取」的结果：什么时候跑的、读了多少轮、模型判出几条、为什么空跑或失败。"
            + "用户问「你最近记了什么」「你是不是没记住」「上次提取成功了吗」这类问题时调用；"
            + "注意它只说**提取过程**，要查具体记下的内容用 recallMemoryFacts。")
    @ToolExecutionPolicy(value = ToolExecutionClass.FAST, allowParallel = true)
    public ToolBusinessResult recentExtractions() {
        String userId = requireCurrentUser();
        List<MemoryExtractionRun> runsList = runs.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 5));
        if (runsList.isEmpty()) {
            return ToolBusinessResult.success("还没有跑过记忆提取（要么刚部署，要么用户还没聊够一轮触发条件）。");
        }
        StringBuilder text = new StringBuilder("最近几次记忆提取：\n");
        for (MemoryExtractionRun run : runsList) {
            text.append("· ").append(run.getCreatedAt() == null ? "-"
                            : run.getCreatedAt().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")))
                    .append("：读了 ").append(run.getWindowTurns() == null ? 0 : run.getWindowTurns()).append(" 轮，");
            if (run.getSkipReason() != null) {
                text.append("跳过（").append(reasonText(run.getSkipReason())).append("）");
            } else {
                text.append("模型判定 ").append(run.getVerdictJson() == null ? "-" : run.getVerdictJson());
            }
            text.append('\n');
        }
        return ToolBusinessResult.success(text.toString().trim());
    }

    /** 跳过原因说人话（与面板「记忆」页同一套口径） */
    private String reasonText(String raw) {
        return switch (raw == null ? "" : raw) {
            case "WINDOW_EMPTY" -> "没有可读轮次";
            case "PRECHECK" -> "这一轮没什么实质内容";
            case "TRANSACTIONAL" -> "全是问课表/设提醒这类不值得记的话";
            case "STALE" -> "结果过期，没写回";
            case "FAILED" -> "调用或解析失败";
            default -> raw;
        };
    }

    /** 按 subject 聚合成"卡片"：一件事一行，行内是「属性 值」；同槽有 DOC 基线时把图上原值也带出来 */
    private String render(List<MemoryFact> facts) {
        Map<String, List<MemoryFact>> bySubject = new LinkedHashMap<>();
        for (MemoryFact fact : facts) {
            bySubject.computeIfAbsent(fact.getSubject() == null ? "（未命名）" : fact.getSubject(),
                    key -> new ArrayList<>()).add(fact);
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, List<MemoryFact>> entry : bySubject.entrySet()) {
            text.append("· ").append(clip(entry.getKey(), 60)).append('：')
                    .append(renderSubject(entry.getValue())).append('\n');
            if (text.length() > CARD_CHARS) {
                text.append("…（还有没列完的，需要更具体的可以再查）\n");
                break;
            }
        }
        return text.toString().trim();
    }

    private String renderSubject(List<MemoryFact> facts) {
        // 同一个属性槽可能有多条：用户说的（USER/AUTO）压过图上看到的（DOC），DOC 原值放括号里
        Map<String, MemoryFact> primary = new LinkedHashMap<>();
        Map<String, MemoryFact> baseline = new LinkedHashMap<>();
        for (MemoryFact fact : facts) {
            String key = (fact.getPredicate() == null || fact.getPredicate().isBlank() ? "备注" : fact.getPredicate())
                    .toLowerCase();
            MemoryFact current = primary.get(key);
            if (MemoryFact.SOURCE_DOC.equals(fact.getSource())) {
                if (current == null) {
                    primary.put(key, fact);
                } else if (!sameObject(current, fact)) {
                    baseline.put(key, fact);
                }
                continue;
            }
            if (current == null || MemoryFact.SOURCE_DOC.equals(current.getSource())) {
                primary.put(key, fact);
            }
        }
        StringBuilder line = new StringBuilder();
        for (Map.Entry<String, MemoryFact> entry : primary.entrySet()) {
            if (line.length() > 0) {
                line.append("；");
            }
            line.append(entry.getKey()).append(' ').append(clip(entry.getValue().getObject(), PER_FACT_CHARS));
            MemoryFact base = baseline.get(entry.getKey());
            if (base != null) {
                line.append("（图上写的是 ").append(clip(base.getObject(), PER_FACT_CHARS)).append("）");
            }
        }
        return line.length() == 0 ? "（没有有效值）" : line.toString();
    }

    private boolean sameObject(MemoryFact left, MemoryFact right) {
        String a = left.getObject() == null ? "" : left.getObject().trim();
        String b = right.getObject() == null ? "" : right.getObject().trim();
        return a.equalsIgnoreCase(b);
    }

    private String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, Math.max(1, max - 1)) + "…";
    }

    private String safe(String value) {
        return clip(value == null ? "" : value, 40);
    }

    private String requireCurrentUser() {
        String userId = statusService.currentUserId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("当前用户上下文不存在");
        }
        return userId;
    }
}
