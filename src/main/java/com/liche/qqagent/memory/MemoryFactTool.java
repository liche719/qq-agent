package com.liche.qqagent.memory;

import com.liche.qqagent.tool.AgentToolProvider;
import com.liche.qqagent.tool.ToolBusinessResult;
import com.liche.qqagent.tool.ToolExecutionClass;
import com.liche.qqagent.tool.ToolExecutionPolicy;
import com.liche.qqagent.tool.ToolStatusService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;

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

    @Tool(value = "查「会变的信息」的当前值：教室/时间/老师/目标分/日程这类他说过、后来又可能改过的事。**建提醒/定时任务时不要先查这里**（直接 parseReminder / createScheduledTask）；**要翻原话用 searchConversation**。")
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
                    + "最近记过的会变信息有这些，看有没有相关：\n" + factService.renderCards(recent, CARD_CHARS));
        }
        return ToolBusinessResult.success(factService.renderCards(hits, CARD_CHARS));
    }

    @Tool(value = "看最近几次记忆提取的结果：读了多少轮、判出几条、为什么空跑或失败。")
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
            case "PARSE_FAILED" -> "模型输出没解析出来（多半是思考吃满额度）";
            case "FAILED" -> "调用或解析失败";
            default -> raw;
        };
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
        return statusService.requireCurrentUserId();
    }
}
