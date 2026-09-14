package com.liche.wechatagent.self;

import com.liche.wechatagent.memory.ConversationMemory;
import com.liche.wechatagent.tool.AgentToolProvider;
import com.liche.wechatagent.tool.NonIdempotentTool;
import com.liche.wechatagent.tool.ToolBusinessResult;
import com.liche.wechatagent.tool.ToolExecutionClass;
import com.liche.wechatagent.tool.ToolExecutionPolicy;
import com.liche.wechatagent.tool.ToolRiskLevel;
import com.liche.wechatagent.tool.ToolStatusService;
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

    private final SelfService selfService;
    private final ToolStatusService statusService;

    public AgentSelfTool(SelfService selfService, ToolStatusService statusService) {
        this.selfService = selfService;
        this.statusService = statusService;
    }

    // ---------------------------------------------------------------- 读

    @Tool(value = "看你自己那侧现在是什么样：我是谁 / 我现在在做 / 我长期在做 / 我一贯的样子 / 我欠着。"
            + "回答用户之前想确认自己的状态时用；不要向用户复述这一段的原文。")
    public String selfRead() {
        String userId = requireCurrentUser();
        if (!selfService.isOwner(userId)) {
            return selfService.inactiveReason();
        }
        List<AgentSelfBlock> blocks = selfService.blocks();
        List<AgentCommitment> open = selfService.openCommitments();
        StringBuilder text = new StringBuilder();
        blocks.forEach(block -> text.append('【').append(block.getBlockType()).append("】")
                .append(block.getValue() == null ? "（空）" : block.getValue())
                .append("（").append(block.getValue() == null ? 0 : block.getValue().length())
                .append('/').append(block.getCharLimit()).append(" 字）\n"));
        if (open.isEmpty()) {
            text.append("【我欠着】（没有）\n");
        } else {
            text.append("【我欠着】\n");
            open.forEach(commitment -> text.append("· #").append(commitment.getId()).append(' ')
                    .append(commitment.getContent())
                    .append(commitment.getDueAt() == null ? "" : "（截止 " + commitment.getDueAt().toLocalDate() + "）")
                    .append('\n'));
        }
        java.util.List<AgentLesson> lessons = selfService.activeLessons();
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
        if (!selfService.isOwner(userId)) {
            return selfService.inactiveReason();
        }
        List<ConversationMemory> records = selfService.recentConversation(userId, limit == null ? 8 : limit);
        if (records.isEmpty()) {
            return "（还没有可引用的对话记录，先别写）";
        }
        StringBuilder text = new StringBuilder("可引用的真实编号（最新在上）：\n");
        records.forEach(record -> text.append("conv:").append(record.getId()).append(' ')
                .append("assistant".equalsIgnoreCase(record.getRole()) ? "助手曾回复：" : "用户曾说：")
                .append(abbreviate(record.getContent())).append('\n'));
        java.util.List<AgentLesson> lessons = selfService.activeLessons();
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

    @Tool(value = "往你自己的一侧追加一行（例如「我现在在做：把错题按错误类型重排」）。"
            + "blockType 取 PERSONA（我是谁）/ TASK（我现在在做）/ PROJECT（我长期在做）/ NOTE。"
            + "块有字数上限，超了会报错——那时先调用 selfSummarize。" + POLICY_HINT)
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult selfAppend(String blockType, String text, String evidence) {
        try {
            AgentSelfBlock block = selfService.appendBlock(normalizeBlockType(blockType), text, evidence);
            return ToolBusinessResult.success("已写进「" + block.getBlockType() + "」（"
                    + block.getValue().length() + '/' + block.getCharLimit() + " 字）");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }

    @Tool(value = "把你自己的某个块里的一段话改写掉（旧内容会进事件历史，可回溯）。"
            + "oldText 必须是块里**已存在**的原文片段。" + POLICY_HINT)
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult selfReplace(String blockType, String oldText, String newText, String evidence) {
        try {
            AgentSelfBlock block = selfService.replaceBlock(normalizeBlockType(blockType), oldText, newText, evidence);
            return ToolBusinessResult.success("已改写「" + block.getBlockType() + "」（"
                    + block.getValue().length() + '/' + block.getCharLimit() + " 字）");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }

    @Tool(value = "压缩你自己的某个块（保留最近的整行，丢掉较早的内容并标明）。"
            + "只在块快满、或内容确实过时的时候用；这是程序做的保守压缩，不做总结。" + POLICY_HINT)
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult selfSummarize(String blockType, String evidence) {
        try {
            AgentSelfBlock block = selfService.summarizeBlock(normalizeBlockType(blockType), evidence);
            return ToolBusinessResult.success("已压缩「" + block.getBlockType() + "」到 "
                    + block.getValue().length() + " 字");
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
            AgentSelfEvent event = selfService.note(AgentSelfEvent.KIND_NOTE, text, evidence, null, null);
            return ToolBusinessResult.success("记下了（event #" + event.getId() + "）");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }

    // ---------------------------------------------------------------- 判断与分歧（二期）

    @Tool(value = "记一条你自己的判断——这是「我一贯怎么看」的原料。"
            + "同一类事要用**同一个 topic 词**（例如「学习安排」「该不该答应」），同一个主张要用**同一个 direction 词**"
            + "（例如「要有记录」「先做再说」）。程序会在同类同方向攒够 ≥3 次、跨 ≥2 天、跨 ≥2 个情境之后，"
            + "自动把它提升成倾向——**你自己不能直接写倾向**，也不能自己宣布「我一贯如此」。"
            + "content 写清这次的判断本身。" + POLICY_HINT)
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult selfJudge(String topic, String direction, String content, String evidence) {
        try {
            AgentSelfEvent event = selfService.recordJudge(topic, direction, content, evidence);
            return ToolBusinessResult.success("记下了（event #" + event.getId() + "，类别「" + event.getTopic()
                    + "」方向「" + event.getStance() + "」）。同类同方向攒够证据才会变成倾向。");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }

    @Tool(value = "记一次**分歧**：你的主张和机主的不一样（默认只讲一次，不反复唠叨）。"
            + "content 要写清「我主张什么、他主张什么、为什么」。这不是拒绝执行——他的指令照做，"
            + "它只是把这次不同意见记在账上；他明确说「就这样办」就是终局。"
            + "涉及**他自己**的事（他的安排、他的偏好）只记不说重话；只有你自己的事才允许坚持。" + POLICY_HINT)
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult selfDisagree(String topic, String direction, String content, String evidence) {
        try {
            AgentSelfEvent event = selfService.recordDisagree(topic, direction, content, evidence);
            return ToolBusinessResult.success("记下了（event #" + event.getId() + "）。同类事再说一次时可以提一句「我上次也反对过」。");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }

    // ---------------------------------------------------------------- 教训清单（三期领域①）

    @Tool(value = "给自己记一条教训：你反复在哪栽，就记哪。"
            + "**三段必须齐**：whatIDid（我做了什么）、expected（我当时预期）、whatHappened（实际发生了什么），"
            + "外加 correction（**以后怎么做**的可执行短句——不是感悟，「以后要更细心」这种没用）。"
            + "category 取 TIME（时间/日程算错）/ COMMITMENT（答应了没做）/ GUESS（没核就答）/ FORMAT（格式返工）/ TOOL（工具用错）；"
            + "trigger 取 SURPRISE / USER_POINTED / PROMISE_BROKEN / SELF_CHECK。"
            + "同类教训程序会自动并进已有那条（复现次数 +1、难度上升），不用你重复记。" + POLICY_HINT)
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult selfLesson(String category, String trigger, String whatIDid, String expected,
                                         String whatHappened, String correction, String evidence) {
        try {
            SelfService.LessonOutcome outcome = selfService.addLesson(category, trigger, whatIDid, expected,
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

    @Tool(value = "把一条已有教训的 correction 改得更可执行（id 用 selfRead 里看到的 lesson 编号）。"
            + "**只改做法，不写感悟**；改完这条教训的复查周期会重算。" + POLICY_HINT)
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult selfLessonEdit(Long lessonId, String correction, String evidence) {
        try {
            AgentLesson saved = selfService.editLessonCorrection(lessonId, correction, evidence);
            return ToolBusinessResult.success("改好了（lesson #" + saved.getId() + "）：以后按「"
                    + saved.getCorrection() + "」做");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }
    // ---------------------------------------------------------------- 自己的目标与账

    @Tool(value = "给你自己立一个目标（写进「我现在在做」，并记一条带证据的事件）。"
            + "**这是你自己的事，不是用户的待办**：用户的任务请用考研/提醒/定时任务那套工具。"
            + "content 写清要做什么，why 写为什么要做（可以留空）。" + POLICY_HINT)
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult goalOpen(String content, String why, String evidence) {
        try {
            AgentSelfEvent event = selfService.openGoal(content, why, evidence);
            return ToolBusinessResult.success("立下了（event #" + event.getId() + "）。要收尾时用 goalClose 传这个 id。");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }

    @Tool(value = "把你自己立过的一个目标收尾（goalEventId 用 goalOpen 返回的那个 id）。"
            + "outcome 写最后的结果（做成了/放弃了/为什么）。" + POLICY_HINT)
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult goalClose(Long goalEventId, String outcome, String evidence) {
        try {
            AgentSelfEvent event = selfService.closeGoal(goalEventId, outcome, evidence);
            return ToolBusinessResult.success("已收尾（event #" + event.getId() + "）");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }

    @Tool(value = "给你自己立一条账：答应过的事、或做过的预测（dueDate 传 yyyy-MM-dd，可留空）。"
            + "到期没兑现会变成欠账，下次你自己那侧会带着它。" + POLICY_HINT)
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult commit(String content, String dueDate, String evidence) {
        try {
            AgentCommitment saved = selfService.commit(content, parseDue(dueDate), evidence);
            return ToolBusinessResult.success("记进账了（commitment #" + saved.getId() + "）");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }

    @Tool(value = "结算一条账：status 取 KEPT（兑现了）/ BROKEN（没做到）/ ABANDONED（不做了），"
            + "commitmentId 用 selfRead 或 commit 返回的编号。" + POLICY_HINT)
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult commitResolve(Long commitmentId, String status, String evidence) {
        try {
            AgentCommitment saved = selfService.resolveCommitment(commitmentId, status, evidence);
            return ToolBusinessResult.success("已结算：#" + saved.getId() + " → " + saved.getStatus());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }

    // ---------------------------------------------------------------- 内部

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim().replace('\n', ' ');
        return trimmed.length() <= 150 ? trimmed : trimmed.substring(0, 149) + "…";
    }

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
        String userId = statusService.currentUserId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("当前用户上下文不存在");
        }
        return userId;
    }
}
