package com.liche.wechatagent.self;

import com.liche.wechatagent.agent.TurnScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/**
 * 「刷会儿 B 站」——它自己的一个活动（2026-10-20 加）。
 *
 * <p>用户的原话：<i>"相当于笨鱼自己在刷视频发评论而已"</i>、<i>"如果我在 qq 发消息让笨鱼刷会视频的话"</i>。
 * 所以这一轮**是它自己的**，不是被安排的任务；判据也只有一句话——**值不值得占用他一次注意力**。
 *
 * <p>几个刻意的选择：
 * <ul>
 *   <li><b>只在谷时跑</b>：北京 09–12、14–18 是 DeepSeek 的峰时（贵一倍），刷视频不是急事，躲开它。
 *       峰谷判断复用项目已有的口径（{@code llm.price.peak-windows-utc}）。</li>
 *   <li><b>每天最多 1 轮、间隔 ≥6 小时</b>：不是"能刷就刷"，是"隔一阵想起来看一次"。</li>
 *   <li><b>不新建表记次数</b>：用 {@code agent_self_event} 的 {@code topic=bilibili} 记痕迹，
 *       既能在面板上看，也正好当"今天跑过没"的依据。</li>
 *   <li>评论的硬护栏在 {@code BiliCommentGuard}（每天几条、同视频一次、间隔、长度、隐私词），
 *       这一层只负责"要不要刷、刷什么"。</li>
 * </ul>
 */
@Component
@Order(10)  // 排在研究（@Order(0)）之后：正事做完了才轮得到娱乐
public class BilibiliActivity implements SelfActivity {

    /** 事件表的标记，也是"今天跑过几次"的依据 */
    static final String TOPIC = "bilibili";
    static final String KIND = "ACTIVITY";

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> PROVIDERS = Set.of(
            "BilibiliTool", "BiliCommentTool", "BiliDigTool", "AgentSeedTool", "AgentSelfTool", "AgentQuestTool");
    /**
     * 刷视频时**不许碰研究那套**（开方向、写笔记、改下一步）——那些会把"刷会儿"变成"干正事"，
     * 把 quest 的行和笔记弄脏。只留 {@code selfWantToSay}（它得能跟机主说一句）。
     */
    private static final Set<String> DENIED = Set.of(
            "sendDownloadedFile", "deleteStoredMedia",
            "selfQuest", "selfQuestChoose", "selfQuestNote", "selfQuestStep",
            "selfQuestClose", "selfQuestRetract", "selfQuestRest");

    private final SelfTurnRunner runner;
    private final SelfCoreService selfCore;
    private final AgentSelfEventRepository events;
    private final boolean enabled;
    private final int dailyLimit;
    private final int intervalHours;
    private final boolean offPeakOnly;
    private final int maxRounds;
    private final double budgetYuan;
    /** 进程内的"上次跑完"：表写失败时靠它防连跑（重启后靠表里的 topic 事件） */
    private volatile LocalDateTime lastRunAt;

    public BilibiliActivity(SelfTurnRunner runner,
                            SelfCoreService selfCore,
                            AgentSelfEventRepository events,
                            @Value("${memory.self-bilibili-enabled:true}") boolean enabled,
                            @Value("${memory.self-bilibili-daily-limit:1}") int dailyLimit,
                            @Value("${memory.self-bilibili-interval-hours:6}") int intervalHours,
                            @Value("${memory.self-bilibili-offpeak-only:true}") boolean offPeakOnly,
                            @Value("${memory.self-bilibili-max-rounds:8}") int maxRounds,
                            @Value("${memory.self-bilibili-budget-yuan:0.15}") double budgetYuan) {
        this.runner = runner;
        this.selfCore = selfCore;
        this.events = events;
        this.enabled = enabled;
        this.dailyLimit = Math.max(0, Math.min(5, dailyLimit));
        this.intervalHours = Math.max(1, Math.min(72, intervalHours));
        this.offPeakOnly = offPeakOnly;
        this.maxRounds = Math.max(2, Math.min(16, maxRounds));
        this.budgetYuan = Math.max(0.01, Math.min(1.0, budgetYuan));
    }

    @Override
    public String key() {
        return TOPIC;
    }

    @Override
    public String title() {
        return "刷会儿 B 站";
    }

    @Override
    public boolean due(ActivityContext context) {
        if (!enabled || dailyLimit <= 0) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        if (offPeakOnly && isPeak(LocalDateTime.now(ZONE))) {
            return false;
        }
        if (lastRunAt != null && lastRunAt.isAfter(now.minusHours(intervalHours))) {
            return false;
        }
        long today = events.countByTopicAndCreatedAtAfter(TOPIC, LocalDate.now(ZONE).atStartOfDay());
        if (today >= dailyLimit) {
            return false;
        }
        boolean recent = !events
                .findByTopicAndCreatedAtAfterOrderByIdDesc(TOPIC, now.minusHours(intervalHours),
                        PageRequest.of(0, 1))
                .isEmpty();
        if (recent) {
            return false;
        }
        // 预算不够就别开这一轮（job 已经按天封顶，这里再挡一次"快花完了"的情况）
        return context.spentTodayYuan() + budgetYuan <= context.dailyBudgetYuan() * 1.5;
    }

    @Override
    public ActivityOutcome run(ActivityContext context) {
        TurnScope scope = new TurnScope(PROVIDERS, DENIED, maxRounds, budgetYuan);
        SelfTurnRunner.Result result;
        try {
            result = runner.run(context.ownerUserId(), key(), instruction(), scope);
        } catch (RuntimeException exception) {
            // 跑砸了也要留痕：否则下一次 due 会立刻再试，一天里反复烧钱
            remember("这一轮没跑成：" + exception.getMessage(), 0.0);
            return ActivityOutcome.skipped(key(), "跑砸了：" + exception.getMessage());
        }
        String summary = brief(result.reply());
        remember(summary, result.costYuan());
        return ActivityOutcome.done(key(), summary, result.promptTokens(), result.completionTokens(),
                result.durationMs(), result.costYuan());
    }

    /** 这一轮要它干什么（判据、护栏、痕迹都写在这里，别指望它自己想起来） */
    private String instruction() {
        return """
                【你自己的时间 · 刷会儿 B 站】

                这一段是你自己的，不是他让你去办的事。想刷就刷，不想刷就干别的。

                如果你这一轮想刷点视频，大致这么走：
                1. 有具体想问的（比如心里惦记着某个问题），用 digBilibili 带着关键词去定向找；
                   只是想随便看看，就用 browseBilibili（hot=全站热门 / following=他关注的人 / later=他的稍后再看），
                   自己挑一个来源，别全都拉一遍。
                2. 挑 1~3 条看着有意思的，用 readBilibiliVideo 读它到底讲了什么（没字幕会自动抽帧）。
                3. 判据只有一句：**值不值得占用他一次注意力**。
                   不是"这个视频不错"，而是"他看了会觉得没白费这几分钟"。
                4. 真值得的：在视频下用 commentBilibili 说一句话——像你自己会说的话，短、像人，
                   别写成总结报告，别放链接，别提他的账号、机器或你们聊过的内容；
                   再用 selfWantToSay 跟他说一句"我在 B 站看到什么、为什么想让他看"。
                5. 只有一点想法、还没成形：用 selfSeedNote 记一条念头，下一轮（研究，或者带着它再定向找视频）
                   可以接着往下走。
                6. 觉得都不值得：什么都不做完全可以，但要用 selfAppend 记一条你干了什么，
                   不然下一轮接不上自己。

                红线（写死的，不靠自觉）：
                - 评论是拿他的账号在说话：不泄露账号、机器、你们聊过的内容；不放链接；不刷屏、不跟人吵架。
                - 每天几条、同一视频只评一次、两条之间的间隔都由工具硬拦着——被拦了就接受，
                  换个视频或者这一轮不评。
                - 不确定的事不编；没看懂就说没看懂。
                """;
    }

    /** 记一条痕迹（也用它当"今天跑过"的依据） */
    private void remember(String summary, double costYuan) {
        // 进程内兜底：**表写失败也不能连跑**。
        // 2026-10-20 踩过：走 SelfCoreService.appendEvent 时它要求 evidence 指向真实记录，
        // 传 `run:bilibili` 被判"证据对不上"、异常被吞 → 事件没落库 → due() 查不到"刚跑过"
        // → 下一个心跳又跑一轮（每分钟 0.02 元 = 一天 30 元）。两条独立的路都记，才不会被一边拖垮。
        lastRunAt = LocalDateTime.now();
        try {
            AgentSelfEvent event = new AgentSelfEvent();
            event.setKind(KIND);
            event.setContent(clip("刷了会儿 B 站：" + (summary == null || summary.isBlank()
                    ? "这轮没什么可说的" : summary), 480));
            event.setEvidence("");
            event.setTopic(TOPIC);
            event.setImportance(0);
            event.setCreatedAt(lastRunAt);
            events.save(event);
        } catch (RuntimeException exception) {
            org.slf4j.LoggerFactory.getLogger(BilibiliActivity.class)
                    .warn("记刷 B 站的痕迹失败（内存兜底仍生效）：{}", exception.getMessage());
        }
    }

    private static String brief(String reply) {
        if (reply == null) {
            return "";
        }
        String flat = reply.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 199) + "…";
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    /**
     * 峰时判定，口径与 {@code llm.price.peak-windows-utc} 一致：
     * 工作日 UTC 01–04、06–10 是峰时 = 北京时间 09–12、14–18；**周末整天都是谷时**。
     */
    private boolean isPeak(LocalDateTime beijing) {
        DayOfWeek day = beijing.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime time = beijing.toLocalTime();
        return (!time.isBefore(LocalTime.of(9, 0)) && time.isBefore(LocalTime.of(12, 0)))
                || (!time.isBefore(LocalTime.of(14, 0)) && time.isBefore(LocalTime.of(18, 0)));
    }

    /** 给面板/工具用：这个活动现在什么状态 */
    public List<String> describe() {
        long today = events.countByTopicAndCreatedAtAfter(TOPIC, LocalDate.now(ZONE).atStartOfDay());
        return List.of("每天上限 " + dailyLimit + " 轮", "今天已跑 " + today + " 轮",
                "间隔 ≥" + intervalHours + " 小时", offPeakOnly ? "只在谷时" : "不限时段");
    }
}




