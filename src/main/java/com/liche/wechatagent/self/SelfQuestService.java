package com.liche.wechatagent.self;

import com.liche.wechatagent.agent.AgentLoop;
import com.liche.wechatagent.agent.ContextTurn;
import com.liche.wechatagent.agent.TurnScope;
import com.liche.wechatagent.agent.TurnTraceStore;
import com.liche.wechatagent.config.LlmSpendMeter;
import com.liche.wechatagent.tool.ToolRegistry;
import com.liche.wechatagent.user.UserProfile;
import com.liche.wechatagent.user.UserService;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 「它自己的时间」的作业主体（三期领域②，spec §7/§9.3）。
 *
 * <p><b>这不是给机主办事的一轮，是它自己的一轮。</b>四条纪律：
 * <ol>
 *   <li><b>独立身份</b>：用 {@link SelfService#SELF_SCOPE} 而不是机主的 userId 跑。借机主的 id 跑会
 *       {@code userService.getOrCreate} + 写 {@code conversation_memory} + 调度记忆提取——
 *       等于把它夜里想的事灌进机主的用户档案和长期记忆。</li>
 *   <li><b>工具是有作用域的</b>：只下发 {@link #QUEST_TOOL_PROVIDERS}，再排掉
 *       {@link #QUEST_DENIED_TOOLS}（会给你发文件的、不可逆删资料的）。</li>
 *   <li><b>预算是钱，不是次数</b>（2026-09-15 用户定）：日预算默认 0.5 元，按
 *       **实际花费**熔断——每轮 prompt 大小差一倍，轮数不是钱的代理。单次先给日预算的一部分，
 *       **没落下产出**（新笔记/新的一步）才允许续一次，当天总封顶 ×1.5。</li>
 *   <li><b>成本入账</b>：钱按 cache 命中/未命中 + 峰谷精算（命中便宜 50 倍），落进
 *       {@code agent_quest_run.cost_yuan}——内存计数一重启就等于免费，那种预算拦不住东西。</li>
 * </ol>
 *
 * <p>为什么"选题"要它自己出：§9 筛选规则第 2 条——靠指派就还是执行器。
 */
@Service
public class SelfQuestService {

    private static final Logger log = LoggerFactory.getLogger(SelfQuestService.class);

    /**
     * 作业里允许的工具类（简单类名）——"权限给大一些，但不可逆的动作不给"。
     *
     * <p>给到它自己能做的事：写自己那侧、管自己的方向与笔记、搜索、读网页、
     * **深想**（`thinkDeeper`——以前被挡在门外，很讽刺：它脑子里最深的工具它自己用不了）、
     * 下载资料、管自己的资料库。
     */
    private static final Set<String> QUEST_TOOL_PROVIDERS = Set.of(
            "AgentSelfTool", "AgentQuestTool", "SearchTool", "WebPageTool", "TimeTool",
            "ThinkingTool", "WebFileTool", "MediaMemoryTool");

    /**
     * 即便在上面那些类里也**不许**下发的两样。
     *
     * <p>**"权限大一些"不等于把不可逆的动作也交出去**，也不等于把机主的东西交给它——
     * 它自己的事不需要动机主那边。
     */
    private static final Set<String> QUEST_DENIED_TOOLS = Set.of("sendDownloadedFile", "deleteStoredMedia");

    /** 一次作业带多少条自己上次的总结当上下文（它自己的"接着上次"）。 */
    private static final int HISTORY_LIMIT = 3;

    /** 一次作业的结果（定时任务日志与面板用） */
    public record Outcome(boolean ran, String reason, Long questId, Long runId, String summary,
                          int promptTokens, int completionTokens, int durationMs,
                          double costYuan, boolean extended, boolean produced) {

        static Outcome skipped(String reason) {
            return new Outcome(false, reason, null, null, null, 0, 0, 0, 0, false, false);
        }
    }

    /** 一次"回合"（可能续期，所以一次调用它自己的时间 = 1~2 个回合） */
    private record Attempt(Long runId, Long questId, String summary, int promptTokens, int completionTokens,
                           int durationMs, double costYuan) {
    }

    private final SelfService selfService;
    private final AgentLoop agentLoop;
    private final UserService userService;
    private final TurnTraceStore turnTraceStore;
    private final ToolRegistry toolRegistry;
    private final LlmSpendMeter spendMeter;
    private final double dailyBudgetYuan;
    private final double budgetOverrunFactor;
    private final double singleBudgetRatio;
    private final int maxRounds;
    private final int minIntervalMinutes;
    private final int interestThreshold;
    private final int idleHours;

    public SelfQuestService(SelfService selfService,
                            AgentLoop agentLoop,
                            UserService userService,
                            TurnTraceStore turnTraceStore,
                            ToolRegistry toolRegistry,
                            LlmSpendMeter spendMeter,
                            @Value("${memory.self-quest-daily-budget-yuan:0.5}") double dailyBudgetYuan,
                            @Value("${memory.self-quest-budget-overrun-factor:1.5}") double budgetOverrunFactor,
                            @Value("${memory.self-quest-single-budget-ratio:0.6}") double singleBudgetRatio,
                            @Value("${memory.self-quest-max-rounds:12}") int maxRounds,
                            @Value("${memory.self-quest-min-interval-minutes:120}") int minIntervalMinutes,
                            @Value("${memory.self-quest-interest-threshold:8}") int interestThreshold,
                            @Value("${memory.self-quest-idle-hours:10}") int idleHours) {
        this.selfService = selfService;
        this.agentLoop = agentLoop;
        this.userService = userService;
        this.turnTraceStore = turnTraceStore;
        this.toolRegistry = toolRegistry;
        this.spendMeter = spendMeter;
        this.dailyBudgetYuan = Math.max(0.01, dailyBudgetYuan);
        this.budgetOverrunFactor = Math.max(1.0, budgetOverrunFactor);
        this.singleBudgetRatio = Math.min(1.0, Math.max(0.1, singleBudgetRatio));
        this.maxRounds = Math.max(1, maxRounds);
        this.minIntervalMinutes = Math.max(0, minIntervalMinutes);
        this.interestThreshold = Math.max(1, interestThreshold);
        this.idleHours = Math.max(1, idleHours);
    }

    /** 当天总共允许花多少（日预算 ×1.5） */
    private double dailyCapYuan() {
        return dailyBudgetYuan * budgetOverrunFactor;
    }

    private TurnScope questScope(double budgetYuan) {
        return new TurnScope(QUEST_TOOL_PROVIDERS, QUEST_DENIED_TOOLS, maxRounds, budgetYuan);
    }

    /**
     * 它现在**想不想动**（2026-09-15 用户定："什么时候想说就什么时候说"）。
     *
     * <p><b>不看钟点</b>：不是"到点了就该干活"，而是"手上有事在推进"或"搁太久了还有没结的事"。
     * 心跳每 10 分钟看一眼，条件够了才真的叫它。预算、防抖、"今天先到这"照旧是硬闸——
     * 触发式不等于无限量。
     */
    public boolean wantsToWork() {
        if (!selfService.isActive()) {
            return false;
        }
        if (selfService.restedToday()) {
            return false;
        }
        if (selfService.questCostToday().doubleValue() >= dailyCapYuan()) {
            return false;
        }
        Duration since = selfService.sinceLastQuest();
        if (since == null) {
            // 还从来没动过：先让它开个头（否则"没有上次"会把它永远锁在门外）
            return true;
        }
        if (since.isNegative() || since.toMinutes() < minIntervalMinutes) {
            return false;
        }
        // ① 它自己的事在推进：自上次作业以来攒下的兴趣够了
        if (selfService.interestSinceLastQuest() >= interestThreshold) {
            return true;
        }
        // ② 搁太久了，而且手上还有没结的事（§8 的 open loop）
        return since.toHours() >= idleHours && selfService.hasOpenLoops();
    }

    /**
     * 跑一次"自己的时间"：单次预算 → 没落产出就续一次 → 封顶。
     *
     * @param trigger 触发者（manul / triggered，记进作业行便于排障）
     */
    public Outcome run(String trigger) {
        if (!selfService.isActive()) {
            return Outcome.skipped(selfService.inactiveReason());
        }
        String owner = selfService.owner();
        if (owner == null) {
            return Outcome.skipped("未配置归属人");
        }
        if (selfService.restedToday()) {
            return Outcome.skipped("它自己说了今天先到这");
        }
        double spentToday = selfService.questCostToday().doubleValue();
        double cap = dailyCapYuan();
        if (spentToday >= cap) {
            return Outcome.skipped(String.format("今天的预算用完了（已花 %.3f 元，封顶 %.2f 元）", spentToday, cap));
        }

        Optional<AgentQuest> active = selfService.activeQuest();
        Long questIdBefore = active.map(AgentQuest::getId).orElse(null);
        int notesBefore = active.map(q -> nz(q.getNoteCount())).orElse(0);
        int stepsBefore = active.map(q -> nz(q.getStepCount())).orElse(0);
        double spentTodayBefore = spentToday;
        double costAtStart = spendMeter == null ? 0 : spendMeter.totalYuan();

        // 单次先给日预算的一部分；当天余量不够就只给余量
        double single = Math.min(dailyBudgetYuan * singleBudgetRatio, cap - spentToday);
        Attempt first = attempt(owner, questIdBefore, single, trigger, false, spentTodayBefore);
        if (first == null) {
            return Outcome.skipped("作业失败（细节见日志）");
        }

        boolean produced = produced(questIdBefore, notesBefore, stepsBefore);
        Attempt last = first;
        boolean extended = false;
        if (!produced) {
            double remaining = cap - selfService.questCostToday().doubleValue();
            if (remaining > 0.02) {
                // **没落下东西**才续：判据是客观的（有没有新笔记/新的一步），不是它自己说"没做完"
                log.info("它这次没落下产出，续一次（还能花 {} 元）", String.format("%.3f", remaining));
                Attempt second = attempt(owner, questIdBefore, remaining, trigger + "+extended", true,
                        spentTodayBefore);
                if (second != null) {
                    last = second;
                    extended = true;
                    produced = produced(questIdBefore, notesBefore, stepsBefore);
                }
            } else {
                log.info("它这次没落下产出，但当天预算已经见底（剩 {} 元），不再续",
                        String.format("%.3f", remaining));
            }
        }

        // 报**这次"自己的时间"总共花了多少**（含续期那一回合），不是只报最后一次——
        // 否则面板上"花 0.09 元"和账上被扣的 0.15 元对不上（实测踩到）。
        double totalCost = Math.max(0, (spendMeter == null ? 0 : spendMeter.totalYuan()) - costAtStart);
        return new Outcome(true, null, last.questId(), last.runId(), last.summary(),
                last.promptTokens(), last.completionTokens(), last.durationMs(),
                totalCost, extended, produced);
    }

    /** 跑一个回合；失败时把作业行标 FAILED 并返回 null（不抛，别让定时线程炸） */
    private Attempt attempt(String owner, Long questId, double budgetYuan, String trigger, boolean extended,
                            double spentTodayBefore) {
        AgentQuestRun run = selfService.startQuestRun(questId, BigDecimal.valueOf(budgetYuan), extended);
        long started = System.nanoTime();
        double costBefore = spendMeter == null ? 0 : spendMeter.totalYuan();
        long hitBefore = spendMeter == null ? 0 : spendMeter.cacheHitTokens();
        long missBefore = spendMeter == null ? 0 : spendMeter.cacheMissTokens();
        try {
            // 把"这一轮它到底有什么权限、多少钱"打成一行：权限面与预算是特意放宽/收紧的，
            // 不写出来就只能靠读代码判断（而且改白名单/预算时一眼能看出有没有生效）。
            List<String> toolNames = toolRegistry.specificationsOf(questScope(budgetYuan)).stream()
                    .map(ToolSpecification::name)
                    .toList();
            log.info("它自己的时间开跑 run={} 方向={} 触发={} 预算={} 元 可用工具 {} 个：{}",
                    run.getId(), questId, trigger, String.format("%.3f", budgetYuan), toolNames.size(),
                    String.join("、", toolNames));
            String reply = agentLoop.chat(SelfService.SELF_SCOPE, null, null, personaOf(owner), null, null,
                    ownHistory(), buildInstruction(run.getId(), selfService.quest(questId).orElse(null),
                            spentTodayBefore, budgetYuan),
                    List.of(), List.of(), null, questScope(budgetYuan));
            int durationMs = (int) Math.max(0, (System.nanoTime() - started) / 1_000_000L);
            int[] tokens = tokensOf();
            double yuan = Math.max(0, (spendMeter == null ? 0 : spendMeter.totalYuan()) - costBefore);
            long hit = Math.max(0, (spendMeter == null ? 0 : spendMeter.cacheHitTokens()) - hitBefore);
            long miss = Math.max(0, (spendMeter == null ? 0 : spendMeter.cacheMissTokens()) - missBefore);
            selfService.finishQuestRun(run, AgentQuestRun.STATUS_RAN, trigger, reply,
                    new SelfService.RunCost(tokens[0], tokens[1], durationMs,
                            BigDecimal.valueOf(yuan), hit, miss));
            Long settledQuest = selfService.activeQuest().map(AgentQuest::getId).orElse(questId);
            log.info("它自己的时间跑完 run={} 花了 {} 元（cache 命中 {}/未命中 {}）用时 {}ms",
                    run.getId(), String.format("%.4f", yuan), hit, miss, durationMs);
            return new Attempt(run.getId(), settledQuest, reply, tokens[0], tokens[1], durationMs, yuan);
        } catch (RuntimeException exception) {
            int durationMs = (int) Math.max(0, (System.nanoTime() - started) / 1_000_000L);
            double yuan = Math.max(0, (spendMeter == null ? 0 : spendMeter.totalYuan()) - costBefore);
            selfService.finishQuestRun(run, AgentQuestRun.STATUS_FAILED,
                    exception.getClass().getSimpleName() + ": " + exception.getMessage(), null,
                    new SelfService.RunCost(0, 0, durationMs, BigDecimal.valueOf(yuan), 0, 0));
            log.warn("它自己的时间跑砸了 run={}：{}", run.getId(), exception.getMessage());
            return null;
        }
    }

    /**
     * 这次作业有没有**落下来东西**——客观判据，不靠它自己说"我做完了"。
     *
     * <p>这是"没完成才续期"的判据：自己说没做完 = 自己给自己发额度，那正是要防的。
     */
    private boolean produced(Long questIdBefore, int notesBefore, int stepsBefore) {
        Optional<AgentQuest> now = selfService.activeQuest();
        if (now.isEmpty()) {
            // 作业前有方向、现在没有 = 它把方向**收掉**了。"不做了 / 挖到底了"本身就是一个结论，
            // 算产出。（实测踩到：它收掉 #1 之后 activeQuest 为空，被判成"没落产出"→ 白续一次、
            // 多花一倍钱。）
            return questIdBefore != null;
        }
        AgentQuest quest = now.get();
        if (questIdBefore == null) {
            return true; // 之前没方向，现在开了一个
        }
        if (!quest.getId().equals(questIdBefore)) {
            return true; // 换了方向，也算落了东西
        }
        return nz(quest.getNoteCount()) > notesBefore || nz(quest.getStepCount()) > stepsBefore;
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    /** 作业指令：只给元指令 + 它自己的状态入口，**不替它选题**（§9 第 2 条）。 */
    private String buildInstruction(Long runId, AgentQuest active, double spentToday, double budgetYuan) {
        StringBuilder text = new StringBuilder();
        text.append("【你自己的时间】\n\n");
        text.append("现在没人在跟你说话。这段时间是给你自己的，不是替机主办事。\n\n");
        text.append("今天已经花掉 ").append(String.format("%.3f", spentToday)).append(" 元（这次给你 ")
                .append(String.format("%.3f", budgetYuan)).append(" 元）。**没人催你**：")
                .append("不想弄就用 selfQuestRest 说一声，今天就不会再叫你了。\n\n");
        if (active == null) {
            text.append("你手上还没有自己的方向。想一个**你自己**真想弄明白的题目——不是机主让你查的——")
                    .append("用 selfQuestChoose 开一个，写清你为什么想弄它。\n");
        } else {
            text.append("你在做的方向是 #").append(active.getId()).append("「").append(active.getTitle()).append("」。\n");
            if (active.getNextStep() != null && !active.getNextStep().isBlank()) {
                text.append("你上次写下的下一步：").append(active.getNextStep()).append('\n');
            }
            text.append("先用 selfQuest 看一眼它和你最近写的东西，再决定这一步做什么。\n");
        }
        text.append("\n你能用的：搜索、读网页、写笔记（带来源）、撤回你自己写错的旧结论、改下一步、")
                .append("换方向或者收掉它。\n");
        text.append("\n规矩：\n");
        text.append("1. **这次至少落一条笔记**（selfQuestNote）：写你**因此得出的**判断，不是资料摘抄；")
                .append("来源能写就写进 sourceUrl。一条笔记都没有 = 这次白跑了。\n");
        text.append("2. **边读边记，别攒到最后**：查 2~3 个来源够下判断了就动手写；")
                .append("宁可留一条带判断的短笔记，也不要读了十个网页什么都没留下。")
                .append("网页别贪多——读进来的正文会一直占着你的上下文，读得越多这一步越贵。\n");
        text.append("3. 别给机主发消息，也别动他的日程/提醒/任务——这是你自己的时间，他的东西现在不归你管。\n");
        text.append("4. 往前推，别刷时长：不要重复你已经会的。查不到就说查不到，别编。\n");
        text.append("5. evidence 用 run:").append(runId).append("（这次作业的编号）；")
                .append("引用已有的方向或笔记时用 quest:<id> / note:<id>。\n");
        text.append("6. **步数有限**：最后一定要用文字收尾，不要把这轮空着结束、也不要输出工具调用格式。\n");
        text.append("7. 有想跟机主说的，用 selfWantToSay 写下来——**他真的会收到**（最晚在下一个时间点发出去，")
                .append("一天最多一条，所以只挑你真想说的那件）。没有想说的就别写：这是你自己的表达，不是汇报。\n");
        text.append("\n收尾那段用不超过 200 字说清：这一步做了什么、学到了什么、下一步打算干什么。\n");
        return text.toString();
    }

    /** 它自己的上次：把最近几轮的总结当历史给它，这样它接得上自己（§8 的"上次停在哪儿"）。 */
    private List<ContextTurn> ownHistory() {
        List<AgentQuestRun> runs = selfService.recentQuestRuns(HISTORY_LIMIT + 1);
        List<ContextTurn> history = new ArrayList<>();
        for (int index = runs.size() - 1; index >= 0; index--) {
            AgentQuestRun run = runs.get(index);
            if (AgentQuestRun.STATUS_RUNNING.equals(run.getStatus()) || run.getSummary() == null
                    || run.getSummary().isBlank()) {
                continue;
            }
            history.add(new ContextTurn("assistant", run.getSummary()));
        }
        return history;
    }

    /** 人设还是它自己那套（同一个人），只是这一轮的任务不是服务机主。 */
    private String personaOf(String owner) {
        try {
            UserProfile profile = userService.get(owner);
            return profile == null ? null : profile.getPersona();
        } catch (RuntimeException exception) {
            log.warn("取人设失败，按没有人设继续：{}", exception.getMessage());
            return null;
        }
    }

    /**
     * 这一轮的 token（面板展示用）。
     *
     * <p>从调用链上汇总而不是让模型自己报：{@link TurnTraceStore} 是流式模型实测的 usage，
     * 已经把思考 token 算在里面（坑 60 的教训）。**钱的账走 {@link LlmSpendMeter}**，
     * 那边还分 cache 命中/未命中。
     */
    private int[] tokensOf() {
        int[] tokens = new int[2];
        turnTraceStore.lastTurn(SelfService.SELF_SCOPE).ifPresent(turn -> turn.steps().stream()
                .filter(step -> "LLM".equals(step.kind()))
                .forEach(step -> {
                    tokens[0] += step.promptTokens();
                    tokens[1] += step.completionTokens();
                }));
        return tokens;
    }
}
