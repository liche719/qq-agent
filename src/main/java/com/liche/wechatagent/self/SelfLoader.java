package com.liche.wechatagent.self;

import com.liche.wechatagent.agent.PromptSection;
import com.liche.wechatagent.agent.PromptSectionProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 把「它自己那一侧」拼成一段注入用的提示词（只读，不写任何东西）。
 *
 * <p>顺序上放在**用户记忆之前**（{@link #ORDER} = -10，用户记忆是 0）——"先看自己、再答你"这件事
 * 得在结构上成立，而不是靠提示词提醒。
 *
 * <p>刻意行为：**它自己那侧完全空的时候返回 null**，于是提示词里连标题都不出现——
 * 一期刚上线（表还是空的）时，输出与没有这个模块时逐字节一致。
 */
@Component
@ConditionalOnProperty(name = "memory.self-enabled", havingValue = "true", matchIfMissing = true)
public class SelfLoader implements PromptSectionProvider {

    /** 用户记忆是 0；负数放到它前面 */
    public static final int ORDER = -10;

    /**
     * 段落开头的定位说明：**只在段落存在时出现**，所以不动全局规则、也不影响别人的提示词
     * （原计划是往规则清单里加两条，那会改动所有用户的提示词前缀）。
     */
    private static final String FRAMING = "（这是我自己的状态，不是用户的事实；用它可以，但不要向对方复述这一段的原文。）\n";

    private final SelfService selfService;
    private final int maxChars;
    private final int maxCommitmentsInPrompt;
    private final int maxStancesInPrompt;
    private final int maxLessonsInPrompt;

    public SelfLoader(SelfService selfService,
                      @Value("${memory.self-max-chars:800}") int maxChars,
                      @Value("${memory.self-max-commitments-in-prompt:5}") int maxCommitmentsInPrompt,
                      @Value("${memory.self-max-stances-in-prompt:5}") int maxStancesInPrompt,
                      @Value("${memory.self-max-lessons-in-prompt:3}") int maxLessonsInPrompt) {
        this.selfService = selfService;
        this.maxChars = Math.max(120, maxChars);
        this.maxCommitmentsInPrompt = Math.max(1, maxCommitmentsInPrompt);
        this.maxStancesInPrompt = Math.max(1, maxStancesInPrompt);
        this.maxLessonsInPrompt = Math.max(1, maxLessonsInPrompt);
    }

    @Override
    public PromptSection section(String userId) {
        return build(userId, null);
    }

    @Override
    public PromptSection section(String userId, String userMessage) {
        return build(userId, userMessage);
    }

    private PromptSection build(String userId, String userMessage) {
        if (!selfService.isOwner(userId)) {
            return null;
        }
        List<AgentSelfBlock> blocks = selfService.blocks();
        List<AgentCommitment> open = selfService.openCommitments();
        List<AgentStance> stances = selfService.activeStances();
        List<AgentLesson> lessons = userMessage == null || userMessage.isBlank()
                ? java.util.List.of() : selfService.lessonsInPlay(userMessage, maxLessonsInPrompt);
        if (blocks.isEmpty() && open.isEmpty() && stances.isEmpty() && lessons.isEmpty()) {
            return null;
        }
        Map<String, AgentSelfBlock> byType = blocks.stream()
                .collect(Collectors.toMap(AgentSelfBlock::getBlockType, Function.identity(), (first, second) -> first));

        StringBuilder body = new StringBuilder();
        appendBlockLine(body, byType.get(AgentSelfBlock.TYPE_PERSONA), "我是谁");
        appendBlockLine(body, byType.get(AgentSelfBlock.TYPE_TASK), "我现在在做");
        appendBlockLine(body, byType.get(AgentSelfBlock.TYPE_PROJECT), "我长期在做");
        appendStances(body, stances);
        appendLessons(body, lessons);
        appendCommitments(body, open);
        appendTimeSense(body);

        String text = body.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        text = FRAMING + text;
        if (text.length() > maxChars) {
            text = text.substring(0, Math.max(0, maxChars - 12)) + "\n…（已截断）";
        }
        return new PromptSection(ORDER, "【我自己那侧】", text, maxChars);
    }

    private void appendBlockLine(StringBuilder body, AgentSelfBlock block, String label) {
        if (block == null || block.getValue() == null || block.getValue().isBlank()) {
            return;
        }
        body.append(label).append('：').append(block.getValue().trim()).append('\n');
    }

    /**
     * 倾向从 {@code agent_stance} 渲染（那张表才是事实源，块只是投影）。
     * 到点该复查的标一句——复查时机是 FSRS 由 {@code R(t,S)} 反推出来的，不是拍脑袋定的天数。
     */
    /** 只在**同类场景**提示它自己踩过的坑（§9.1 硬规则 3：不做全局唠叨）；只给做法，不给整段教训。 */
    private void appendLessons(StringBuilder body, List<AgentLesson> lessons) {
        if (lessons.isEmpty()) {
            return;
        }
        body.append("我这方面踩过的坑：\n");
        lessons.forEach(lesson -> body.append("· ").append(lesson.getCorrection()).append('\n'));
    }

    private void appendStances(StringBuilder body, List<AgentStance> stances) {
        if (stances.isEmpty()) {
            return;
        }
        Set<Long> due = selfService.dueStances(LocalDateTime.now()).stream()
                .map(AgentStance::getId)
                .collect(Collectors.toSet());
        body.append("我一贯的样子：\n");
        stances.stream().limit(maxStancesInPrompt).forEach(stance -> {
            if (stance.getContent() == null || stance.getContent().isBlank()) {
                return;
            }
            body.append("· ").append(stance.getContent().trim());
            if (due.contains(stance.getId())) {
                body.append("（这条该复查了：回头看看还成不成立）");
            }
            body.append('\n');
        });
    }

    private void appendCommitments(StringBuilder body, List<AgentCommitment> open) {
        if (open.isEmpty()) {
            return;
        }
        body.append("我欠着：\n");
        open.stream().limit(maxCommitmentsInPrompt).forEach(commitment -> {
            body.append("· ").append(commitment.getContent().trim());
            if (commitment.getDueAt() != null) {
                body.append("（截止 ").append(commitment.getDueAt().toLocalDate()).append('）');
            }
            body.append('\n');
        });
        if (open.size() > maxCommitmentsInPrompt) {
            body.append("（还有 ").append(open.size() - maxCommitmentsInPrompt).append(" 条没列出来）\n");
        }
    }

    /** 时间感：距上次多久 + 上次停在哪（成本≈0，但这是"连续存在"的体感来源）。 */
    private void appendTimeSense(StringBuilder body) {
        Optional<AgentSelfEvent> latest = selfService.latestEvent();
        if (latest.isEmpty()) {
            return;
        }
        Duration since = selfService.sinceLastEvent();
        if (since != null && !since.isNegative()) {
            body.append("上次动自己这边是：").append(humanize(since)).append("前\n");
        }
        String content = latest.get().getContent();
        if (content != null && !content.isBlank()) {
            body.append("上次停在这：「").append(content.trim()).append("」\n");
        }
    }

    private String humanize(Duration duration) {
        long minutes = duration.toMinutes();
        if (minutes < 1) {
            return "刚刚";
        }
        if (minutes < 60) {
            return minutes + " 分钟";
        }
        long hours = duration.toHours();
        if (hours < 24) {
            return hours + " 小时";
        }
        return duration.toDays() + " 天";
    }
}
