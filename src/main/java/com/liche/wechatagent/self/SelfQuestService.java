package com.liche.wechatagent.self;

import com.liche.wechatagent.agent.AgentLoop;
import com.liche.wechatagent.agent.ContextTurn;
import com.liche.wechatagent.agent.TurnScope;
import com.liche.wechatagent.agent.TurnTraceStore;
import com.liche.wechatagent.tool.ToolRegistry;
import com.liche.wechatagent.user.UserProfile;
import com.liche.wechatagent.user.UserService;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
 *       等于把它夜里想的事灌进机主的用户档案和长期记忆。第一优先级是"用户长期记忆不丢失"，
 *       污染它就是损坏它。</li>
 *   <li><b>工具是有作用域的</b>：只下发 {@link #ALLOWED_TOOL_PROVIDERS} 这五个类
 *       （它自己的记录 + 搜索 + 读网页 + 时间）。没有提醒、没有定时任务、没有考试、没有发消息——
 *       它在自己的时间里**动不了机主的东西**。</li>
 *   <li><b>预算是硬的</b>：每天 {@code self-quest-daily-call-limit} 次，跳过/失败也占额度
 *       （否则失败重试能把预算吃穿）；再加一道防抖，手动触发与定时不会前后脚跑两次。</li>
 *   <li><b>成本入账</b>：token 写进 {@code agent_quest_run}（坑 60：思考 token 也算进 max_tokens）。</li>
 * </ol>
 *
 * <p>为什么"选题"要它自己出：§9 筛选规则第 2 条——靠指派就还是执行器。
 * 所以这里**只给元指令**（"把这件事往前推，别重复已经会的"）+ 它自己的状态，题目由它自己定；
 * 它的选择理由会被记下来——偏好是稀缺下的选择模式被记录下来，不是宣称出来的。
 */
@Service
public class SelfQuestService {

    private static final Logger log = LoggerFactory.getLogger(SelfQuestService.class);

    /**
     * 「它自己的时间」的作用域：**权限给大一些，但不可逆的动作不给**。
     *
     * <p>给到它自己能做的事：写自己那侧、管自己的方向与笔记、搜索、读网页、
     * **深想**（`thinkDeeper`——以前被挡在门外，很讽刺：它脑子里最深的工具它自己用不了）、
     * 下载资料、管自己的资料库。
     *
     * <p>排除两样：`sendDownloadedFile`（会把文件推到机主 QQ 上——口先不开）、
     * `deleteStoredMedia`（不可逆地删资料）。**"权限大一些"不等于把不可逆的动作也交出去**，
     * 也不等于把机主的东西交给它——它自己的事不需要动机主那边。
     *
     * <p>轮数 12（普通对话是 8）：它做自己的事时应该能多走几步。
     */
    private static final TurnScope QUEST_SCOPE = new TurnScope(
            Set.of("AgentSelfTool", "AgentQuestTool", "SearchTool", "WebPageTool", "TimeTool",
                    "ThinkingTool", "WebFileTool", "MediaMemoryTool"),
            Set.of("sendDownloadedFile", "deleteStoredMedia"),
            12);

    /** 一次作业带多少条自己上次的总结当上下文（它自己的"接着上次"）。 */
    private static final int HISTORY_LIMIT = 3;

    /** 一次作业的结果（定时任务日志与面板用） */
    public record Outcome(boolean ran, String reason, Long questId, Long runId, String summary,
                          int promptTokens, int completionTokens, int durationMs) {

        static Outcome skipped(String reason) {
            return new Outcome(false, reason, null, null, null, 0, 0, 0);
        }
    }

    private final SelfService selfService;
    private final AgentLoop agentLoop;
    private final UserService userService;
    private final TurnTraceStore turnTraceStore;
    private final ToolRegistry toolRegistry;
    private final int dailyCallLimit;
    private final int minIntervalMinutes;

    public SelfQuestService(SelfService selfService,
                            AgentLoop agentLoop,
                            UserService userService,
                            TurnTraceStore turnTraceStore,
                            ToolRegistry toolRegistry,
                            @Value("${memory.self-quest-daily-call-limit:1}") int dailyCallLimit,
                            @Value("${memory.self-quest-min-interval-minutes:180}") int minIntervalMinutes) {
        this.selfService = selfService;
        this.agentLoop = agentLoop;
        this.userService = userService;
        this.turnTraceStore = turnTraceStore;
        this.toolRegistry = toolRegistry;
        this.dailyCallLimit = Math.max(1, dailyCallLimit);
        this.minIntervalMinutes = Math.max(0, minIntervalMinutes);
    }

    /**
     * 跑一次"自己的时间"。手动入口与定时入口共用（预算和防抖都在里面）。
     *
     * @param trigger 触发者（记进作业行，便于排障：是谁把它叫起来的）
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
        if (selfService.questRunsToday() >= dailyCallLimit) {
            return Outcome.skipped("今天自己的时间已经用完了（" + dailyCallLimit + " 次）");
        }
        LocalDateTime now = LocalDateTime.now();
        Optional<AgentQuestRun> last = selfService.lastQuestRun();
        if (last.isPresent() && last.get().getCreatedAt() != null && minIntervalMinutes > 0) {
            long minutes = Duration.between(last.get().getCreatedAt(), now).toMinutes();
            if (minutes < minIntervalMinutes) {
                return Outcome.skipped("距上次才 " + Math.max(0, minutes) + " 分钟（防抖间隔 "
                        + minIntervalMinutes + " 分钟）");
            }
        }

        Optional<AgentQuest> active = selfService.activeQuest();
        Long questId = active.map(AgentQuest::getId).orElse(null);
        long usedToday = selfService.questRunsToday();
        // 先建作业行：它的 id 是这次作业里所有写入的锚点证据（run:<id>）——
        // 没有它，"第一次开方向"会因为没有合法证据而永远失败（坑 64 的证据死锁）
        AgentQuestRun run = selfService.startQuestRun(questId);
        long started = System.nanoTime();
        try {
            // 把"这一轮它到底有什么权限"打成一行日志：权限面是这次特意放宽的，
            // 不写出来就只能靠读代码判断（而且加工具/改白名单时一眼能看出有没有生效）。
            List<String> toolNames = toolRegistry.specificationsOf(QUEST_SCOPE).stream()
                    .map(ToolSpecification::name)
                    .toList();
            log.info("它自己的时间开跑 run={} 方向={} 触发={} 可用工具 {} 个：{}",
                    run.getId(), questId, trigger, toolNames.size(), String.join("、", toolNames));
            String reply = agentLoop.chat(SelfService.SELF_SCOPE, null, null, personaOf(owner), null, null,
                    ownHistory(), buildInstruction(run.getId(), active.orElse(null), usedToday),
                    List.of(), List.of(), null, QUEST_SCOPE);
            int durationMs = (int) Math.max(0, (System.nanoTime() - started) / 1_000_000L);
            int[] tokens = tokensOf();
            selfService.finishQuestRun(run, AgentQuestRun.STATUS_RAN, trigger, reply,
                    tokens[0], tokens[1], durationMs);
            log.info("自己的时间跑完 run={} 方向={} 触发={} tokens={}/{} 用时 {}ms",
                    run.getId(), questId, trigger, tokens[0], tokens[1], durationMs);
            return new Outcome(true, null, questId, run.getId(), reply, tokens[0], tokens[1], durationMs);
        } catch (RuntimeException exception) {
            int durationMs = (int) Math.max(0, (System.nanoTime() - started) / 1_000_000L);
            selfService.finishQuestRun(run, AgentQuestRun.STATUS_FAILED,
                    exception.getClass().getSimpleName() + ": " + exception.getMessage(), null, 0, 0, durationMs);
            log.warn("自己的时间跑砸了 run={}：{}", run.getId(), exception.getMessage());
            return Outcome.skipped("作业失败：" + exception.getMessage());
        }
    }

    /** 作业指令：只给元指令 + 它自己的状态入口，**不替它选题**（§9 第 2 条）。 */
    private String buildInstruction(Long runId, AgentQuest active, long usedToday) {
        StringBuilder text = new StringBuilder();
        text.append("【你自己的时间】\n\n");
        text.append("现在没人在跟你说话。这段时间是给你自己的，不是替机主办事。\n\n");
        text.append("今天的机会：这是第 ").append(usedToday + 1).append(" 次，今天最多 ")
                .append(dailyCallLimit).append(" 次。**额度是上限、不是任务**——今天不想弄就用 selfQuestRest ")
                .append("说一声，今天剩下的机会就不会再叫你了。\n\n");
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
        text.append("7. 有想跟机主说、现在又说不出去的，用 selfWantToSay 记下来（**他收不到**，只是记着）；")
                .append("没有想说的就别记——这是你自己的表达，不是汇报。\n");
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
     * 这一轮的 token（成本入账）。
     *
     * <p>从调用链上汇总而不是让模型自己报：{@link TurnTraceStore} 是流式模型实测的 usage，
     * 已经把思考 token 算在里面（坑 60 的教训）。
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
