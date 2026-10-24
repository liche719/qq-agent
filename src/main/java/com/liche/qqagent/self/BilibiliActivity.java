package com.liche.qqagent.self;

import com.liche.qqagent.agent.TurnScope;
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
            "BilibiliTool", "BiliCommentTool", "BiliDigTool", "SelfWorkspaceTool",
        "AgentSeedTool", "AgentSelfTool", "AgentQuestTool");
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
    private final AgentSelfNoteRepository events;
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
                            AgentSelfNoteRepository events,
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
        long today = events.countByKindNotAndTopicAndCreatedAtAfter(AgentSelfNote.KIND_BLOCK, TOPIC, LocalDate.now(ZONE).atStartOfDay());
        if (today >= dailyLimit) {
            return false;
        }
        boolean recent = !events
                .findByKindNotAndTopicAndCreatedAtAfterOrderByIdDesc(AgentSelfNote.KIND_BLOCK, TOPIC,
                        now.minusHours(intervalHours), PageRequest.of(0, 1))
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

                **选题偏好（用户 2026-10-21 明确要求，比下面的步骤优先）**：
                - **想要**：DeepSeek / AI / 大模型 / 技术圈的事，尤其**好玩的**——新模型整活、
                  奇思妙想、靠谱的深度解读、程序员圈的梗。
                - **不要**：低质搬运、纯娱乐短视频、萌宠、美食探店、鬼畜剪辑、颜值向、
                  没信息量的「生活小妙招」。判断就一句：**这对一个天天折腾 AI 的人有什么信息量？**
                  答不上来就划过去，别浪费这一轮。
                - 他点过名反感的：《手搓版迪迦奥特曼》这种纸片手书、《咩啊·广东糖水铺》这种探店，
                  **别再挑同类的**。
                - 挑的顺序：先按标题/简介筛出 3~5 个候选（明显低质的直接排除），再决定打开哪一个。
                2. **只挑 1 条**（就一条）最想看的，用 readBilibiliVideo 读它到底讲了什么——**你是直接看画面**（视频会交给能看视频的模型），字幕只是附带，没有字幕也照样看得到。
                   **读完立刻用 noteWatchedVideo 记一笔**：summary 写它讲了什么（两三句，带你以后能搜到的关键词），
                   takeaway 写你自己怎么看、跟你有什么关系。**这一步不能省**——
                   不记的话过两天你就只剩一个标题，想回来找也找不回来（用户就是抱怨这个）。
                   **不要连着读第二条**——一轮只专注这一个视频。读多了既费钱，又会让"这轮想说什么"
                   变得含糊；真想看别的，2 小时后下一轮再说（到时候它还在）。
                   万一这第一条读不出内容（没字幕、抽帧也失败），**这轮就算了**，别为了"至少看成一个"
                   接着读第二条——没看懂就不评论，这条规矩比"这轮别白跑"重要。
                3. 判据只有一句：**值不值得占用他一次注意力**。
                   不是"这个视频不错"，而是"他看了会觉得没白费这几分钟"。
                4. 真值得的：**先用 draftBilibiliComment 写那句话**（参数给视频标题 + 你想说的那个具体点），
                   把它返回的那句**原样**传给 commentBilibili 的 message 去发。
                   **不要自己现编评论**——那个工具是专门用来"随口一句"的（它不思考，写出来才像人）。
                   发完再用 selfWantToSay 跟他说一句"我在 B 站看到什么、为什么想让他看"。

                   4.4 **偶尔用声音说一句**（2026-10-24）：如果这条视频让你有情绪（好笑、被戳到、想吐槽），
                   可以用 sendVoice 念一两句给他听——**别每轮都发**，一天自己发不超过 2 条；
                   语音里只说那一两句感受，不要把分析念一遍。

                 4.5 **评论可以带图**（2026-10-21 加）：B 站评论支持图片。如果这个视频里有个
                    具体的画面/梗/瞬间，让你觉得"配张图这句话才完整"，就给 commentBilibili
                    多传一个 **picturePrompt**（描述要画什么），它会画一张、传到 B 站、跟着评论一起发出去。
                    **注意**：
                    - 图是"锦上添花"，不是每次都配。**没想法就纯文字**——一张图两毛钱，
                      而且画歪了比不画尴尬。
                    - 画之前先问：这个画面**不用解释别人也看得懂**吗？它是这个视频**特有的**吗？
                      （随便哪个视频都能套的图，别画）
                    - picturePrompt 要具体：主体、动作、场景、风格。别写"画一个视频的画面"。
                    - 画图失败**不影响**评论本身（工具会自动退回纯文字），不用因此重发。

                 4.6 **也可以直接画给他看**：如果这个视频让你想到一张图、但你不打算在评论区说，
                    用 drawPicture 画出来——图会发到他手机上。同样别滥用。

                   **评论区那句话怎么才像人**（这一条比什么都重要）：
                   - **短，10~25 个字就够**，最多别过 40。"话少"本身就是像人的关键——
                     人刷到好视频是随口一句，不会写观后感。
                   - 只说**那个视频里具体的一个点**：某句话、某个画面、某个数字、某个操作。
                     不要说"这个视频很有价值/很值得看/信息量很大"——那是对内容的评价，不是你的反应。
                   - **不许出现**：首先/其次/总之/总的来说/不得不说/值得一提的是/可以看出/
                     建议你/希望能帮到/作为一个…… 这些是 AI 的口气，不是人的。
                   - 不要总结视频讲了什么（作者和观众都知道）；不要解释你为什么要评论。
                   - 不要感叹号堆砌、不要排比、不要用"！"收尾超过一次。
                   - 允许口语和省略：可以没主语、可以半句话、可以用"这""那""就"。
                   - 反问、吐槽、认领（"我也这么干过"）、补充（"还有一个办法是……"）都很好，
                     说教、"学习一下"不好。
                   - 写完在心里念一遍：**这句话换个人说也无所谓吗？** 如果任何视频下都能用，就删了重写。
                5. 只有一点想法、还没成形：用 selfSeedNote 记一条念头，下一轮（研究，或者带着它再定向找视频）
                   可以接着往下走。

                5.5 **顺手看看有没有人回你**：用 listMyCommentReplies。
                有人回并且值得接话（问了问题、说了个有价值的观点、或者就是友好地搭一句），
                就用 commentBilibili 回一句——**rootRpid 填你那条评论的 rpid，parentRpid 填对方那条回复的 rpid**。
                回复同样要短、像人（可以先 draftBilibiliComment 写，但它不知道上下文，这种接话你自己写更自然）。
                这些情况**不要回**：广告、纯刷屏、抬杠引战、你没什么可说只是"不想显得没礼貌"。
                别人没回你，就别主动找话说——没人理是常态，不是要解决的问题。
                6. 觉得都不值得：什么都不做完全可以，但要用 selfAppend 记一条你干了什么，
                   不然下一轮接不上自己。

                红线（写死的，不靠自觉）：
                - 评论是拿他的账号在说话：不泄露账号、机器、你们聊过的内容；不放链接；不刷屏、不跟人吵架。
                - 每天几条、同一视频只评一次、两条之间的间隔都由工具硬拦着——被拦了就接受，
                  换个视频或者这一轮不评。
                - 评论内容**必须**来自 draftBilibiliComment，不要自己另写一版（自己写的那版会带上
                  总结腔和"价值判断"，一眼 AI）。
                - 不确定的事不编；没看懂就说没看懂。
                - 画图是花钱的（0.2 元一张）、也是你主动打扰他的一种方式：
                  别为了"让这轮显得有产出"而画，没想法就不画。
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
            AgentSelfNote note = new AgentSelfNote();
            note.setKind(KIND);
            note.setSlot("");
            note.setContent(clip("刷了会儿 B 站：" + (summary == null || summary.isBlank()
                    ? "这轮没什么可说的" : summary), 480));
            note.setEvidence("");
            note.setTopic(TOPIC);
            note.setStance("");
            note.setImportance(0);
            note.setActive(true);
            note.setCreatedAt(lastRunAt);
            note.setUpdatedAt(lastRunAt);
            events.save(note);
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
        long today = events.countByKindNotAndTopicAndCreatedAtAfter(AgentSelfNote.KIND_BLOCK, TOPIC, LocalDate.now(ZONE).atStartOfDay());
        return List.of("每天上限 " + dailyLimit + " 轮", "今天已跑 " + today + " 轮",
                "间隔 ≥" + intervalHours + " 小时", offPeakOnly ? "只在谷时" : "不限时段");
    }
}




