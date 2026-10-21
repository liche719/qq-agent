package com.liche.wechatagent.agent;

import com.liche.wechatagent.channel.ProactiveDelivery;
import com.liche.wechatagent.channel.WeChatChannel;
import com.liche.wechatagent.tool.AgentToolProvider;
import com.liche.wechatagent.tool.ToolBusinessResult;
import com.liche.wechatagent.tool.ToolExecutionClass;
import com.liche.wechatagent.tool.ToolExecutionPolicy;
import com.liche.wechatagent.tool.ToolStatusService;
import com.liche.wechatagent.user.UserProfile;
import com.liche.wechatagent.user.UserService;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把「要干很久的活」扔到后台，干完主动汇报（2026-10-21）。
 *
 * <p>用户的原话：<i>「有时候它做事情做很久，但是也没有回我汇报给我……她发现要做好久，
 * 就会先回我一下然后接着做，不用占用发消息的窗口，这时候我也能给她发消息正常互动，
 * 后面做完了也会回复我汇报给我」</i>。
 *
 * <p>原来的局限：一条用户消息 = 一个**同步**流程，跑完之前下一句话要么排队要么被合并；
 * {@code sayToUser} 只能"先说一句"，说了之后还是占着这条线。
 *
 * <p>现在的做法：模型判断这活要久，就调这个工具——<b>立刻返回</b>（主流程马上能继续接新消息），
 * 真正的活在**单线程池**里跑，跑完用 {@link ProactiveDelivery} 主动发一条汇报。
 *
 * <p><b>为什么单线程</b>：机器只有 2 核 1.8G，跑两个 Agent 循环会互相拖慢。
 * 排队比并发好——用户能接受"等一会儿"，不能接受"两个都卡"。
 *
 * <p><b>边界（都写死在这，不靠模型自觉）</b>：
 * <ul>
 *   <li>队列最多 {@code maxQueue} 个，超了直接拒绝——不然它会一口气扔十个进来；</li>
 *   <li>后台轮**不许再调本工具**（{@link TurnScope#denies}），否则会无限套娃；</li>
 *   <li>后台轮有**轮数上限和钱预算**，跑飞了自己会停；</li>
 *   <li>失败/超时都**主动说一声**，不能让它静默消失；</li>
 *   <li>进程重启会让在跑的任务丢失——这是单机实现的已知代价，汇报里会说明。</li>
 * </ul>
 */
@Component
public class BackgroundTaskTool implements AgentToolProvider {

    private static final Logger log = LoggerFactory.getLogger(BackgroundTaskTool.class);

    /** 后台任务里禁用的工具名：本工具自己（防递归） */
    private static final Set<String> DENIED_IN_BACKGROUND = Set.of("workInBackground");

    /**
     * 用 ObjectProvider 而不是直接注入 AgentLoop（2026-10-21 踩到）：
     * AgentLoop → ToolRegistry → 本工具 → AgentLoop 会形成循环依赖，Spring 默认禁止，
     * 启动直接 APPLICATION FAILED TO START。用 Provider 就是**用到时才取**，构造期不建边。
     */
    private final org.springframework.beans.factory.ObjectProvider<AgentLoop> agentLoopProvider;
    /** 后台任务花的钱也要记账（原来完全没进今日花费） */
    private final com.liche.wechatagent.config.LlmSpendMeter spendMeter;
    /**
     * 汇报要写回对话记忆（2026-10-21 修）：不写的话，用户追问「查完了吗」时
     * 它看不到自己已经汇报过，会答「还没弄好」——因为它真的不记得。
     */
    private final org.springframework.beans.factory.ObjectProvider<com.liche.wechatagent.memory.ConversationMemoryService> memoryProvider;
    /** 任务落库（2026-10-21）：进程重启不能把用户等的汇报弄丢 */
    private final BackgroundTaskRepository tasks;
    private final ToolStatusService statusService;
    private final UserService userService;
    private final List<WeChatChannel> channels;

    private final int maxQueue;
    private final int maxRounds;
    private final double budgetYuan;

    /** 已经在排队或正在跑的任务数 */
    private final AtomicInteger pending = new AtomicInteger();

    private final ExecutorService runner = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "background-task-runner");
        thread.setDaemon(true);
        return thread;
    });

    public BackgroundTaskTool(org.springframework.beans.factory.ObjectProvider<AgentLoop> agentLoopProvider,
                              com.liche.wechatagent.config.LlmSpendMeter spendMeter,
                              org.springframework.beans.factory.ObjectProvider<com.liche.wechatagent.memory.ConversationMemoryService> memoryProvider,
                              BackgroundTaskRepository tasks,
                              ToolStatusService statusService,
                              UserService userService,
                              List<WeChatChannel> channels,
                              @Value("${agent.background.max-queue:3}") int maxQueue,
                              @Value("${agent.background.max-rounds:12}") int maxRounds,
                              @Value("${agent.background.budget-yuan:0.8}") double budgetYuan) {
        this.agentLoopProvider = agentLoopProvider;
        this.spendMeter = spendMeter;
        this.memoryProvider = memoryProvider;
        this.tasks = tasks;
        this.statusService = statusService;
        this.userService = userService;
        this.channels = channels;
        this.maxQueue = Math.max(1, maxQueue);
        this.maxRounds = Math.max(2, maxRounds);
        this.budgetYuan = Math.max(0.05, budgetYuan);
    }

    @Tool(value = "把一件**要干很久**的事放到后台去做，做完你主动汇报给他。"
            + "用它之后这一轮立刻结束——他能马上继续跟你说话（你也可以正常回他），"
            + "后台任务跑完会自己给他发一条消息。"
            + "**什么时候用**：搜索+读好几个网页、翻好几条视频、写长东西这类估计要几分钟的活。"
            + "**什么时候别用**：一句话能答的、要立刻拿到结果的、需要来回确认的——那些直接做。"
            + "task 要写清楚**做完之后要知道什么**（比如「查一下 XX 最近的进展，重点看发布时间和版本号」），"
            + "后台那一轮是**独立**的，看不到你们现在的聊天，所以别写「按刚才说的」这种指代。")
    @ToolExecutionPolicy(value = ToolExecutionClass.FAST, hasSideEffect = false,
            riskLevel = com.liche.wechatagent.tool.ToolRiskLevel.LOW, allowParallel = false)
    public ToolBusinessResult workInBackground(String task) {
        String userId = statusService.currentUserId();
        if (userId == null || userId.isBlank()) {
            return ToolBusinessResult.failure("拿不到当前用户是谁，没法放后台。");
        }
        if (task == null || task.isBlank()) {
            return ToolBusinessResult.failure("没说要做什么。");
        }
        int now = pending.get();
        if (now >= maxQueue) {
            return ToolBusinessResult.failure("后台已经排着 " + now + " 件事了（上限 " + maxQueue
                    + "）。**这次直接做**，或者跟他说'手上还有活，这个等下弄'。");
        }

        final String goal = task.trim();
        // 先落库再排队：万一进程在这之后立刻重启，启动时还能捞回来
        Long taskId = null;
        try {
            BackgroundTask row = new BackgroundTask();
            row.setUserId(userId);
            row.setGoal(goal);
            row.setStatus(BackgroundTask.STATUS_QUEUED);
            row.setCreatedAt(java.time.LocalDateTime.now());
            taskId = tasks.save(row).getId();
        } catch (RuntimeException exception) {
            log.warn("后台任务落库失败（继续跑，但重启会丢）：{}", exception.getMessage());
        }
        final Long savedId = taskId;

        pending.incrementAndGet();
        runner.submit(() -> {
            markRunning(savedId);
            double costBefore = spendMeter == null ? 0 : spendMeter.totalYuan();
            long started = System.nanoTime();
            try {
                String reply = runInBackground(userId, goal);
                double yuan = Math.max(0, (spendMeter == null ? 0 : spendMeter.totalYuan()) - costBefore);
                long ms = Math.max(0, (System.nanoTime() - started) / 1_000_000L);
                log.info("后台任务完成 user={} 用时={}ms 花费={}元", userId, ms, String.format("%.3f", yuan));
                markDone(savedId, reply);
                deliver(userId, "（刚才那件事做完了）" + (reply == null ? "" : reply.trim()));
            } catch (Throwable error) {
                log.warn("后台任务失败 user={} goal={} err={}", userId,
                        goal.length() > 60 ? goal.substring(0, 60) + "…" : goal, error.toString());
                markFailed(savedId, shortReason(error));
                deliver(userId, "刚才那件事没做成（" + shortReason(error) + "）。要不再试一次？");
            } finally {
                pending.decrementAndGet();
            }
        });
        return ToolBusinessResult.success("已经放到后台了，你这一轮说一句「行，我去弄，好了喊你」就行，"
                + "然后**马上结束这轮**——他后面说的话你照常回，不用等后台那件事。");
    }

    /** 后台真正跑的那一轮：独立上下文、受作用域约束 */
    private String runInBackground(String userId, String goal) {
        UserProfile profile = null;
        String persona = null;
        try {
            profile = userService.get(userId);
            if (profile != null) {
                persona = profile.getPersona();
            }
        } catch (RuntimeException exception) {
            log.warn("后台任务取人设失败，按没有人设继续：{}", exception.getMessage());
        }
        TurnScope scope = new TurnScope(Set.of(), DENIED_IN_BACKGROUND, maxRounds, budgetYuan);
        AgentLoop agentLoop = agentLoopProvider.getIfAvailable();
        if (agentLoop == null) {
            throw new IllegalStateException("AgentLoop 还没就绪");
        }
        return agentLoop.chat(userId, null, null, persona, null, null,
                List.of(), goal, List.of(), List.of(), null, scope);
    }

    /** 主动发一条（走现有的投递与配额） */
    private void deliver(String userId, String text) {
        try {
            UserProfile profile = userService.get(userId);
            boolean sent = ProactiveDelivery.send(channels, profile, userId, text);
            // 发出去之后写进对话记忆：这样用户追问时它能看到"我已经汇报过了"
            try {
                var memory = memoryProvider == null ? null : memoryProvider.getIfAvailable();
                if (memory != null) {
                    memory.record(userId, "assistant", null, text, java.util.List.of(), (java.time.LocalDateTime) null);
                }
            } catch (RuntimeException ignored) {
                // 写记忆失败不能影响已经发出去的汇报
            }
            if (!sent) {
                log.warn("后台任务汇报没发出去 user={}", userId);
            }
        } catch (RuntimeException exception) {
            log.warn("后台任务汇报发送失败 user={} err={}", userId, exception.toString());
        }
    }

    private static String shortReason(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message.length() > 60 ? message.substring(0, 60) + "…" : message;
    }
    private void markRunning(Long id) {
        updateStatus(id, BackgroundTask.STATUS_RUNNING, row -> row.setStartedAt(java.time.LocalDateTime.now()));
    }

    private void markDone(Long id, String result) {
        updateStatus(id, BackgroundTask.STATUS_DONE, row -> {
            row.setFinishedAt(java.time.LocalDateTime.now());
            row.setResult(clip(result, 4000));
        });
    }

    private void markFailed(Long id, String error) {
        updateStatus(id, BackgroundTask.STATUS_FAILED, row -> {
            row.setFinishedAt(java.time.LocalDateTime.now());
            row.setError(clip(error, 480));
        });
    }

    private void updateStatus(Long id, String status, java.util.function.Consumer<BackgroundTask> extra) {
        if (id == null) {
            return;
        }
        try {
            tasks.findById(id).ifPresent(row -> {
                row.setStatus(status);
                if (extra != null) {
                    extra.accept(row);
                }
                tasks.save(row);
            });
        } catch (RuntimeException exception) {
            log.warn("更新后台任务状态失败 id={} status={} err={}", id, status, exception.getMessage());
        }
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "\u2026";
    }

    /**
     * 启动时把上次残留的任务捞回来（2026-10-21）。
     *
     * <p>两条路：
     * <ul>
     *   <li><b>RUNNING</b>：进程在它跑着的时候挂了，活断在半路——标 FAILED 并主动告诉他，
     *       不能让它静默消失；</li>
     *   <li><b>QUEUED</b>：只是还没排到，重新扔进池子接着跑。</li>
     * </ul>
     */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void resumeAfterRestart() {
        try {
            List<BackgroundTask> leftovers = tasks.findByStatusInOrderByCreatedAtAsc(
                    List.of(BackgroundTask.STATUS_QUEUED, BackgroundTask.STATUS_RUNNING));
            if (leftovers.isEmpty()) {
                return;
            }
            log.info("启动时发现 {} 条没跑完的后台任务", leftovers.size());
            for (BackgroundTask row : leftovers) {
                if (BackgroundTask.STATUS_RUNNING.equals(row.getStatus())) {
                    markFailed(row.getId(), "服务重启，这条没跑完");
                    deliver(row.getUserId(), "刚才那件事（" + clip(row.getGoal(), 40)
                            + "）因为服务重启没做完。要我再试一次吗？");
                    continue;
                }
                final String goal = row.getGoal();
                final Long id = row.getId();
                final String owner = row.getUserId();
                pending.incrementAndGet();
                runner.submit(() -> {
                    markRunning(id);
                    try {
                        String reply = runInBackground(owner, goal);
                        markDone(id, reply);
                        deliver(owner, "（刚才那件事做完了）" + (reply == null ? "" : reply.trim()));
                    } catch (Throwable error) {
                        markFailed(id, shortReason(error));
                        deliver(owner, "刚才那件事没做成（" + shortReason(error) + "）。要不再试一次？");
                    } finally {
                        pending.decrementAndGet();
                    }
                });
            }
        } catch (RuntimeException exception) {
            log.warn("恢复后台任务失败（不影响启动）：{}", exception.getMessage());
        }
    }
}
