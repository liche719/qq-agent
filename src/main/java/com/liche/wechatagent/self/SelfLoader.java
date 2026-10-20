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

    private final SelfCoreService selfCore;
    private final SelfStanceService selfStances;
    private final SelfLessonService selfLessons;
    private final SelfQuestStore selfQuests;
    private final AgentSeedService selfSeeds;
    private final int maxChars;
    private final int maxCommitmentsInPrompt;
    private final int maxStancesInPrompt;
    private final int maxLessonsInPrompt;
    private final int maxSeedsInPrompt;
    private final int maxRecentEvents;

    public SelfLoader(SelfCoreService selfCore,
                      SelfStanceService selfStances,
                      SelfLessonService selfLessons,
                      SelfQuestStore selfQuests,
                      AgentSeedService selfSeeds,
                      @Value("${memory.self-max-chars:800}") int maxChars,
                      @Value("${memory.self-max-commitments-in-prompt:5}") int maxCommitmentsInPrompt,
                      @Value("${memory.self-max-stances-in-prompt:5}") int maxStancesInPrompt,
                      @Value("${memory.self-max-lessons-in-prompt:3}") int maxLessonsInPrompt,
                      @Value("${memory.self-max-seeds-in-prompt:5}") int maxSeedsInPrompt,
                      @Value("${memory.self-max-recent-events:3}") int maxRecentEvents) {
        this.selfCore = selfCore;
        this.selfStances = selfStances;
        this.selfLessons = selfLessons;
        this.selfQuests = selfQuests;
        this.selfSeeds = selfSeeds;
        this.maxChars = Math.max(120, maxChars);
        this.maxCommitmentsInPrompt = Math.max(1, maxCommitmentsInPrompt);
        this.maxStancesInPrompt = Math.max(1, maxStancesInPrompt);
        this.maxLessonsInPrompt = Math.max(1, maxLessonsInPrompt);
        this.maxSeedsInPrompt = Math.max(1, maxSeedsInPrompt);
        this.maxRecentEvents = Math.max(1, maxRecentEvents);
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
        if (!selfCore.isOwner(userId)) {
            return null;
        }
        List<AgentSelfBlock> blocks = selfCore.blocks();
        List<AgentCommitment> open = selfCore.openCommitments();
        List<AgentStance> stances = selfStances.activeStances();
        Optional<AgentQuest> quest = selfQuests.activeQuest();
        List<AgentLesson> lessons = userMessage == null || userMessage.isBlank()
                ? java.util.List.of() : selfLessons.lessonsInPlay(userMessage, maxLessonsInPrompt);
        // 2026-10-20：加上"最近发生的事"和"还没消化的念头"——用户要的是活动之间相互知道
        // （刷到视频 → 有想法 → 研究 → 再定向找），这两段是那个"知道"的落点。
        List<AgentSeed> seeds = selfSeeds.openSeeds(maxSeedsInPrompt);
        List<AgentSelfEvent> recent = selfCore.recentEvents(maxRecentEvents);
        if (blocks.isEmpty() && open.isEmpty() && stances.isEmpty() && lessons.isEmpty() && quest.isEmpty()
                && seeds.isEmpty() && recent.size() <= 1) {
            return null;
        }
        Map<String, AgentSelfBlock> byType = blocks.stream()
                .collect(Collectors.toMap(AgentSelfBlock::getBlockType, Function.identity(), (first, second) -> first));

        StringBuilder body = new StringBuilder();
        appendBlockLine(body, byType.get(AgentSelfBlock.TYPE_PERSONA), "我是谁");
        appendBlockLine(body, byType.get(AgentSelfBlock.TYPE_TASK), "我现在在做");
        appendBlockLine(body, byType.get(AgentSelfBlock.TYPE_PROJECT), "我长期在做");
        appendQuest(body, quest.orElse(null));
        appendRecentEvents(body, recent);
        appendSeeds(body, seeds);
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

    /**
     * 它在做的**自己的方向**（三期领域②）——一行，从 {@code memory.self-max-chars} 的预算里出。
     *
     * <p>为什么只给一行：它是"我有自己的事"最直接的体现，也是它接得上自己的钩子；
     * 但它是**它自己的事**，不该在给机主的回复里长篇展开（§9.3 的笔记走面板看，不进对话预算）。
     */
    private void appendQuest(StringBuilder body, AgentQuest quest) {
        if (quest == null || quest.getTitle() == null || quest.getTitle().isBlank()) {
            return;
        }
        body.append("我自己的方向：").append(SelfText.clipLine(quest.getTitle(), 60));
        if (quest.getNextStep() != null && !quest.getNextStep().isBlank()) {
            body.append("（下一步：").append(SelfText.clipLine(quest.getNextStep(), 80)).append('）');
        }
        body.append('\n');
    }

    /**
     * 我最近干过什么（2026-10-20 加）。
     *
     * <p>原来只给"上次停在这"一条，而用户要的是"刷完视频能接上后面的事"——只给一条接不上。
     * 这里给最近几条，按时间正序（老的在前，读起来像一条时间线），一行一条、只给结论。
     */
    private void appendRecentEvents(StringBuilder body, List<AgentSelfEvent> recent) {
        if (recent == null || recent.isEmpty()) {
            return;
        }
        body.append("我最近干的：\n");
        recent.stream()
                .filter(event -> event.getContent() != null && !event.getContent().isBlank())
                .forEach(event -> body.append("· ").append(SelfText.clipLine(event.getContent(), 60)).append('\n'));
    }

    /**
     * 还没消化的念头（2026-10-20 加）——这是活动之间"相辅相成"的接头。
     *
     * <p>写成"惦记着"而不是"待办清单"：它不是一个必须清空的队列，而是"我脑子里还挂着的事"，
     * 可以接着做、也可以就这么放着。
     */
    private void appendSeeds(StringBuilder body, List<AgentSeed> seeds) {
        if (seeds == null || seeds.isEmpty()) {
            return;
        }
        body.append("我心里还惦记着：\n");
        seeds.forEach(seed -> {
            if (seed.getContent() == null || seed.getContent().isBlank()) {
                return;
            }
            body.append("· #").append(seed.getId()).append(' ').append(seed.getContent().trim());
            if (seed.getSource() != null && !seed.getSource().isBlank()) {
                body.append("（来自 ").append(seed.getSource()).append('）');
            }
            body.append('\n');
        });
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
        Set<Long> due = selfStances.dueStances(LocalDateTime.now()).stream()
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
        Optional<AgentSelfEvent> latest = selfCore.latestEvent();
        if (latest.isEmpty()) {
            return;
        }
        Duration since = selfCore.sinceLastEvent();
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
