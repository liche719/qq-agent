package com.liche.qqagent.self;

import com.liche.qqagent.agent.TurnScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;

/**
 * 「主动说一句」（2026-10-24，用户要的"活人感"第 3 条）。
 *
 * <p><b>为什么不是"每 2 小时判断一次要不要说话"</b>：那样信噪比天生低——它总会"想"出点什么来
 * （记得吃饭、今天降温），很快就变成骚扰。所以这里只做**低频签到**：默认一天最多 1 次、
 * 两次之间至少隔 4 小时、**夜里 23:00–08:00 不打扰**，而且判据写死成"不说会错过、说了不烦人"，
 * 没命中就一个字都不发。
 *
 * <p>它说出去的话走既有链路：这一轮里调 {@code selfWantToSay} 把想说的话记进
 * {@code agent_self_utterance}，心跳结束前的 {@code SelfSpeakService.flush()} 负责真正投递
 * （那边有每天上限、通道不可用会标 SUPPRESSED）。
 */
@Component
@Order(30) // 排在其他活动之后：正事和娱乐都做完了，再想"有没有话要说"
public class ProactiveCheckinActivity implements SelfActivity {

    private static final Logger log = LoggerFactory.getLogger(ProactiveCheckinActivity.class);
    static final String TOPIC = "checkin";
    static final String KIND = "ACTIVITY";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 只给只读工具 + 记录想说的话；不许改任务、不许开新方向 */
    private static final Set<String> PROVIDERS = Set.of(
            "AgentQuestTool", "AgentSelfTool", "ConversationSearchTool", "ReminderTool",
            "ScheduledTaskTool", "MemoryFactTool", "SearchTool", "WebPageTool");

    private static final Set<String> DENIED = Set.of(
            "sendDownloadedFile", "deleteStoredMedia",
            "selfQuest", "selfQuestChoose", "selfQuestNote", "selfQuestStep",
            "selfQuestClose", "selfQuestRetract", "selfQuestRest",
            "commentBilibili", "draftBilibiliComment", "noteWatchedVideo", "readBilibiliVideo");

    private final SelfTurnRunner runner;
    private final AgentSelfNoteRepository events;
    private final boolean enabled;
    private final int dailyLimit;
    private final int intervalHours;
    private final int quietStartHour;
    private final int quietEndHour;
    private final int maxRounds;
    private final double budgetYuan;
    /** 进程内兜底：表写失败时也不能连跑 */
    private volatile LocalDateTime lastRunAt;

    public ProactiveCheckinActivity(SelfTurnRunner runner,
                                    AgentSelfNoteRepository events,
                                    @Value("${memory.self-checkin-enabled:true}") boolean enabled,
                                    @Value("${memory.self-checkin-daily-limit:1}") int dailyLimit,
                                    @Value("${memory.self-checkin-interval-hours:4}") int intervalHours,
                                    @Value("${memory.self-checkin-quiet-start:23}") int quietStartHour,
                                    @Value("${memory.self-checkin-quiet-end:8}") int quietEndHour,
                                    @Value("${memory.self-checkin-max-rounds:4}") int maxRounds,
                                    @Value("${memory.self-checkin-budget-yuan:0.05}") double budgetYuan) {
        this.runner = runner;
        this.events = events;
        this.enabled = enabled;
        this.dailyLimit = Math.max(0, Math.min(3, dailyLimit));
        this.intervalHours = Math.max(1, Math.min(24, intervalHours));
        this.quietStartHour = Math.max(0, Math.min(23, quietStartHour));
        this.quietEndHour = Math.max(0, Math.min(23, quietEndHour));
        this.maxRounds = Math.max(2, Math.min(8, maxRounds));
        this.budgetYuan = Math.max(0.01, Math.min(0.5, budgetYuan));
    }

    /** 运行时开关（面板关掉自主模块时，这个活动也不该跑） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SelfSwitch selfSwitch;

    @Override
    public String key() {
        return TOPIC;
    }

    @Override
    public String title() {
        return "主动说一句";
    }

    @Override
    public boolean due(ActivityContext context) {
        if (selfSwitch != null && !selfSwitch.enabled()) {
            return false;
        }
        if (!enabled || dailyLimit <= 0) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now(ZONE);
        if (inQuietHours(now.getHour())) {
            return false;
        }
        long today = events.countByKindNotAndTopicAndCreatedAtAfter(
                AgentSelfNote.KIND_BLOCK, TOPIC, LocalDate.now(ZONE).atStartOfDay());
        if (today >= dailyLimit) {
            return false;
        }
        LocalDateTime last = lastRunAt;
        if (last != null && last.isAfter(now.minusHours(intervalHours))) {
            return false;
        }
        boolean recent = !events.findByKindNotAndTopicAndCreatedAtAfterOrderByIdDesc(
                AgentSelfNote.KIND_BLOCK, TOPIC, now.minusHours(intervalHours), PageRequest.of(0, 1)).isEmpty();
        if (recent) {
            return false;
        }
        return context.spentTodayYuan() + budgetYuan <= context.dailyBudgetYuan() * 1.5;
    }

    @Override
    public ActivityOutcome run(ActivityContext context) {
        TurnScope scope = new TurnScope(PROVIDERS, DENIED, maxRounds, budgetYuan);
        SelfTurnRunner.Result result;
        try {
            result = runner.run(context.ownerUserId(), key(), instruction(), scope);
        } catch (RuntimeException exception) {
            remember("这一轮没跑成：" + exception.getMessage(), 0.0);
            return ActivityOutcome.skipped(key(), "跑砸了：" + exception.getMessage());
        }
        String summary = brief(result.reply());
        remember(summary, result.costYuan());
        return ActivityOutcome.done(key(), summary, result.promptTokens(), result.completionTokens(),
                result.durationMs(), result.costYuan());
    }

    /** 这一轮要它干什么：判据、护栏、怎么说话都写死 */
    private String instruction() {
        return """
                【你自己的时间 · 想想有没有话要说】

                这一段不是他让你办的事，是你自己想：**现在有没有一件值得主动跟他说的事**。

                可以先看一眼这些（需要才查，别为了查而查）：
                - searchConversation 翻翻最近聊过什么，有没有他提过、但还没解决的事；
                - listReminders / listScheduledTasks 看有没有快到点、或者该提醒他的事。

                判据只有一句：**不说会错过，说了不烦人**。
                - 值得：他最近在忙的那件事有进展、你答应过但还没做的事、快到期的事。
                - 不值得：天气、吃饭、早点睡、"今天怎么样"这类谁都会说的客套；
                  也别为了显得懂事而硬找一句——**没人喜欢每天定点来一句关心**。

                真觉得值得说，就调 selfWantToSay：content 是你要对他说的那一两句原话
                （口语、不超过 60 字、别提"任务/系统/检测到"这种词），why 写你为什么想说。
                **没有值得说的，就什么都别调，直接回复 NONE。**
                """;
    }

    private boolean inQuietHours(int hour) {
        if (quietStartHour == quietEndHour) {
            return false;
        }
        if (quietStartHour < quietEndHour) {
            return hour >= quietStartHour && hour < quietEndHour;
        }
        // 跨零点：23 点到次日 8 点
        return hour >= quietStartHour || hour < quietEndHour;
    }

    private void remember(String summary, double costYuan) {
        lastRunAt = LocalDateTime.now(ZONE);
        try {
            AgentSelfNote note = new AgentSelfNote();
            note.setKind(KIND);
            note.setSlot("");
            note.setContent(clip("想了想有没有话要说：" + (summary == null || summary.isBlank()
                    ? "这轮没说" : summary), 480));
            note.setEvidence("");
            note.setTopic(TOPIC);
            note.setStance("");
            note.setImportance(0);
            note.setActive(true);
            note.setCreatedAt(lastRunAt);
            note.setUpdatedAt(lastRunAt);
            events.save(note);
        } catch (RuntimeException exception) {
            log.warn("记主动说的痕迹失败（内存兜底仍生效）：{}", exception.getMessage());
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
        return com.liche.qqagent.support.Texts.clip(text, max);
    }
}
