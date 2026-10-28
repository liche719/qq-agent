package com.liche.qqagent.self;

import com.liche.qqagent.memory.ConversationMemory;
import com.liche.qqagent.tool.AgentToolProvider;
import com.liche.qqagent.tool.NonIdempotentTool;
import com.liche.qqagent.tool.ToolBusinessResult;
import com.liche.qqagent.tool.ToolExecutionClass;
import com.liche.qqagent.tool.ToolExecutionPolicy;
import com.liche.qqagent.tool.ToolRiskLevel;
import com.liche.qqagent.tool.ToolStatusService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 自主模块的工具面：**这些是"它自己的事"，不是为用户做的事**。
 *
 * <p>三条贯穿所有工具的规矩（写在描述里，也由服务层强制）：
 * <ol>
 *   <li>每次写入都要带 {@code evidence}（{@code conv:<对话id>} 或 {@code event:<事件id>}），对不上就拒；</li>
 *   <li>块有字数上限，满了要先 summarize；</li>
 *   <li>只对机主生效（没配归属人时所有调用都会返回"暂不工作"）。</li>
 * </ol>
 * 写操作一律 {@link NonIdempotentTool} + {@code retryable=false}：重试会重复写入（坑 39）。
 */
@Component
@ConditionalOnProperty(name = "memory.self-enabled", havingValue = "true", matchIfMissing = true)
public class AgentSelfTool implements AgentToolProvider {

    private static final String POLICY_HINT = "证据必须来自真实记录：先调 selfRecall 拿到真实的 conv:<id>，"
            + "或用你自己工具返回的 event:<id>；你手上没有别的编号来源，编一个一定被拒。"
            + "没有证据就不要调用本工具。";

    private final SelfCoreService selfCore;
    private final SelfStanceService selfStances;
    private final SelfLessonService selfLessons;
    private final ToolStatusService statusService;

    public AgentSelfTool(SelfCoreService selfCore, SelfStanceService selfStances, SelfLessonService selfLessons,
                         ToolStatusService statusService) {
        this.selfCore = selfCore;
        this.selfStances = selfStances;
        this.selfLessons = selfLessons;
        this.statusService = statusService;
    }

    // ---------------------------------------------------------------- 读

    @Tool(value = "看你自己那侧现在是什么样：我是谁 / 我现在在做 / 我长期在做 / 我一贯的样子 / 我欠着。"
            + "回答用户之前想确认自己的状态时用；不要向用户复述这一段的原文。")
    public String selfRead() {
        String userId = requireCurrentUser();
        if (!selfCore.isOwner(userId)) {
            return selfCore.inactiveReason();
        }
        List<AgentSelfBlock> blocks = selfCore.blocks();
        StringBuilder text = new StringBuilder();
        blocks.forEach(block -> text.append('【').append(block.getBlockType()).append("】")
                .append(block.getValue() == null ? "（空）" : block.getValue())
                .append("（").append(block.getValue() == null ? 0 : block.getValue().length())
                .append('/').append(block.getCharLimit()).append(" 字）\n"));
        java.util.List<AgentLesson> lessons = selfLessons.activeLessons();
        text.append("【我的教训清单】\n");
        if (lessons.isEmpty()) {
            text.append("（还没有）\n");
        } else {
            lessons.stream().limit(5).forEach(lesson -> text.append("· #").append(lesson.getId())
                    .append(' ').append(lesson.getCategory()).append("（复现 ").append(lesson.getRecurrenceCount())
                    .append(" 次）：").append(lesson.getCorrection()).append('\n'));
        }
        return text.toString().trim();
    }

    @Tool(value = "看你跟机主最近的对话记录，每条都带**真实编号**（conv:<id>）。"
            + "写自己那侧之前先调用它：evidence 只能用这里真实出现过的 conv:<id>，"
            + "或者你自己工具返回过的 event:<id>。这里没有的编号一律会被当成编造拒绝。")
    public String selfRecall(Integer limit) {
        String userId = requireCurrentUser();
        if (!selfCore.isOwner(userId)) {
            return selfCore.inactiveReason();
        }
        List<ConversationMemory> records = selfCore.recentConversation(userId, limit == null ? 8 : limit);
        if (records.isEmpty()) {
            return "（还没有可引用的对话记录，先别写）";
        }
        StringBuilder text = new StringBuilder("可引用的真实编号（最新在上）：\n");
        records.forEach(record -> text.append("conv:").append(record.getId()).append(' ')
                .append("assistant".equalsIgnoreCase(record.getRole()) ? "助手曾回复：" : "用户曾说：")
                .append(SelfText.clipLine(record.getContent(), 150)).append('\n'));
        java.util.List<AgentLesson> lessons = selfLessons.activeLessons();
        text.append("【我的教训清单】\n");
        if (lessons.isEmpty()) {
            text.append("（还没有）\n");
        } else {
            lessons.stream().limit(5).forEach(lesson -> text.append("· #").append(lesson.getId())
                    .append(' ').append(lesson.getCategory()).append("（复现 ").append(lesson.getRecurrenceCount())
                    .append(" 次）：").append(lesson.getCorrection()).append('\n'));
        }
        return text.toString().trim();
    }

    // ---------------------------------------------------------------- 写自己的块

    @Tool(value = "往你自己那一侧追加一行（如「我现在在做：…」）。blockType 取 PERSONA/DOING/LONG_TERM/STANCE/OWED。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult selfAppend(String blockType, String text, String evidence) {
        try {
            AgentSelfBlock block = selfCore.appendBlock(normalizeBlockType(blockType), text, evidence);
            return ToolBusinessResult.success("已写进「" + block.getBlockType() + "」（"
                    + block.getValue().length() + '/' + block.getCharLimit() + " 字）");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }

    @Tool(value = "随手记一条你自己的事（进你自己的时间线，不进常驻的块）。"
            + "适合记「我做了/我注意到/我改主意了」这类以后可能有用的东西。" + POLICY_HINT)
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult selfNote(String text, String evidence) {
        try {
            AgentSelfEvent event = selfCore.note(AgentSelfEvent.KIND_NOTE, text, evidence, null, null);
            return ToolBusinessResult.success("记下了（event #" + event.getId() + "）");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }

    // ---------------------------------------------------------------- 判断与分歧（二期）

    @Tool(value = "记一条你自己的判断（「我一贯怎么看」的原料）。同一类事用同一个 topic 词，方便以后归纳。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult selfJudge(String topic, String direction, String content, String evidence) {
        try {
            AgentSelfEvent event = selfStances.recordJudge(topic, direction, content, evidence);
            return ToolBusinessResult.success("记下了（event #" + event.getId() + "，类别「" + event.getTopic()
                    + "」方向「" + event.getStance() + "」）。同类同方向攒够证据才会变成倾向。");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }

    @Tool(value = "记一次**分歧**：你的主张和机主不一样。写清我主张什么、他主张什么、我为什么还这么想；默认只讲一次。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult selfDisagree(String topic, String direction, String content, String evidence) {
        try {
            AgentSelfEvent event = selfStances.recordDisagree(topic, direction, content, evidence);
            return ToolBusinessResult.success("记下了（event #" + event.getId() + "）。同类事再说一次时可以提一句「我上次也反对过」。");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }

    // ---------------------------------------------------------------- 教训清单（三期领域①）

    @Tool(value = "给自己记一条教训：反复栽在哪就记哪。三段必须齐：我做了什么(whatIDid)、当时预期(expected)、该怎么改。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult selfLesson(
            @dev.langchain4j.agent.tool.P("教训的类别（如 工具调用/检索/表达）") String category,
            @dev.langchain4j.agent.tool.P("什么情境下会犯（触发条件）") String trigger,
            @dev.langchain4j.agent.tool.P("我当时具体做了什么") String whatIDid,
            @dev.langchain4j.agent.tool.P("我当时预期会怎样") String expected,
            @dev.langchain4j.agent.tool.P("实际发生了什么") String whatHappened,
            @dev.langchain4j.agent.tool.P("下次该怎么改（可执行的一条）") String correction,
            @dev.langchain4j.agent.tool.P("证据：对话编号 conv:<id> 或文件路径") String evidence) {
        try {
            SelfLessonService.LessonOutcome outcome = selfLessons.addLesson(category, trigger, whatIDid, expected,
                    whatHappened, correction, evidence);
            if (outcome.recurred()) {
                return ToolBusinessResult.success("这条跟已有教训是同一类，已经并进去了（复现第 "
                        + outcome.lesson().getRecurrenceCount() + " 次，难度升到 "
                        + Math.round(outcome.lesson().getDifficulty()) + "）");
            }
            return ToolBusinessResult.success("记下了（lesson #" + outcome.lesson().getId() + "，类别 "
                    + outcome.lesson().getCategory() + "）。这类事下次我会先按 correction 做。");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }

    // ---------------------------------------------------------------- 自己的目标与账

    // ---------------------------------------------------------------- 内部

    private String normalizeBlockType(String blockType) {
        String normalized = blockType == null ? "" : blockType.trim().toUpperCase();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("blockType 不能为空（PERSONA / TASK / PROJECT / NOTE）");
        }
        return normalized;
    }

    /** 只接受 yyyy-MM-dd 或 yyyy-MM-ddTHH:mm；只给日期时算当天 23:59（当天结束前）。 */
    private LocalDateTime parseDue(String dueDate) {
        if (dueDate == null || dueDate.isBlank()) {
            return null;
        }
        String text = dueDate.trim();
        try {
            if (text.length() <= 10) {
                return LocalDate.parse(text).atTime(LocalTime.of(23, 59));
            }
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("日期格式不对，用 yyyy-MM-dd（例如 2026-09-20）");
        }
    }

    private String requireCurrentUser() {
        return statusService.requireCurrentUserId();
    }
}

