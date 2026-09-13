package com.liche.wechatagent.schedule;

import com.liche.wechatagent.agent.AgentOrchestrator;
import com.liche.wechatagent.channel.InboundMessage;
import com.liche.wechatagent.channel.ProactiveDelivery;
import com.liche.wechatagent.channel.WeChatChannel;
import com.liche.wechatagent.exception.BizException;
import com.liche.wechatagent.log.UserLogService;
import com.liche.wechatagent.log.UserScope;
import com.liche.wechatagent.user.UserProfile;
import com.liche.wechatagent.user.UserProfileRepository;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 定时任务：创建 / 列出 / 启停 / 删除 / 立即执行。
 *
 * <p>调度复用项目已有的 Quartz（JDBC 持久化，`QRTZ_*` 表），任务组用 {@code scheduled-tasks}，
 * 与提醒（组 {@code reminders}）互不干扰；因此**不需要改表结构**。
 * 到点后由 {@link ScheduledTaskJob} 走完整的 Agent 链路执行并把结果推给用户。
 */
@Service
public class ScheduledTaskService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskService.class);
    private static final String GROUP = "scheduled-tasks";
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    private final ScheduledTaskRepository repository;
    private final ScheduledTaskParseService parseService;
    /**
     * 用 ObjectProvider 延迟取 AgentOrchestrator：它间接依赖命令注册表（/schedules），
     * 而命令处理器又依赖本服务，直接注入会形成 Bean 循环依赖。
     */
    private final ObjectProvider<AgentOrchestrator> orchestratorProvider;
    private final List<WeChatChannel> channels;
    private final UserProfileRepository profileRepository;
    private final UserLogService userLogService;
    private final Scheduler scheduler;
    private final ZoneId zone;
    private final boolean enabled;
    private final int maxPerUser;
    private final int resultMaxChars;

    /** 手动触发的执行放后台线程，避免在一次对话里嵌套执行 Agent */
    private final java.util.concurrent.ExecutorService runner =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "scheduled-task-runner");
                thread.setDaemon(true);
                return thread;
            });

    @Autowired
    public ScheduledTaskService(ScheduledTaskRepository repository,
                                ScheduledTaskParseService parseService,
                                ObjectProvider<AgentOrchestrator> orchestratorProvider,
                                List<WeChatChannel> channels,
                                UserProfileRepository profileRepository,
                                UserLogService userLogService,
                                Scheduler scheduler,
                                @Value("${scheduled.enabled:true}") boolean enabled,
                                @Value("${scheduled.max-per-user:10}") int maxPerUser,
                                @Value("${scheduled.result-max-chars:2000}") int resultMaxChars,
                                @Value("${app.time-zone:Asia/Shanghai}") String timeZoneId) {
        this.repository = repository;
        this.parseService = parseService;
        this.orchestratorProvider = orchestratorProvider;
        this.channels = channels;
        this.profileRepository = profileRepository;
        this.userLogService = userLogService;
        this.scheduler = scheduler;
        this.enabled = enabled;
        this.maxPerUser = Math.max(1, Math.min(100, maxPerUser));
        // 上限对齐 lastResult 的列宽（2000），配大了也写不进库
        this.resultMaxChars = Math.max(200, Math.min(2000, resultMaxChars));
        this.zone = parseZone(timeZoneId);
    }

    /** 启动时把库里启用的任务同步进 Quartz（容器重建/时钟漂移后自动恢复） */
    @EventListener(ApplicationReadyEvent.class)
    public void resyncOnStartup() {
        if (!enabled) {
            return;
        }
        int restored = 0;
        for (ScheduledTask task : repository.findByEnabledTrue()) {
            try {
                if (!scheduler.checkExists(jobKey(task.getId()))) {
                    schedule(task);
                    restored++;
                }
                clearStaleRunning(task);
                refreshNextRun(task);
            } catch (Exception exception) {
                log.warn("定时任务恢复失败 id={} title={}: {}", task.getId(), task.getTitle(), exception.getMessage());
            }
        }
        if (restored > 0) {
            log.info("已恢复 {} 个定时任务到调度器", restored);
        }
    }

    // ---------------- 对外能力（聊天工具与面板都用这些） ----------------

    /** 自然语言创建：LLM 解析出标题/指令/Cron 后落库并调度 */
    public String createFromDescription(String userId, String description) {
        requireUser(userId);
        if (description == null || description.isBlank()) {
            return "没听清要定时做什么，可以用「每天早上 8 点把天气发我」这样的说法。";
        }
        ScheduledTaskParseService.ParsedTask parsed = parseService.parse(description);
        if (parsed.cron() == null) {
            String missing = parsed.missing().isEmpty() ? "执行频率" : String.join("；", parsed.missing());
            return "还差点信息，没定下来：" + missing + "。比如「每天早上 8 点……」或「每周一 9 点……」。";
        }
        return create(userId, parsed.title(), parsed.instruction(), parsed.cron());
    }

    /** 结构化创建（面板表单与工具直连都走这里） */
    public String create(String userId, String title, String instruction, String cron) {
        requireUser(userId);
        if (!enabled) {
            return "定时任务功能当前关闭（scheduled.enabled=false）。";
        }
        String normalizedCron = ScheduledTaskParseService.normalizeCron(cron);
        if (!ScheduledTaskParseService.isValidCron(normalizedCron)) {
            return "Cron 表达式不合法，需要 Quartz 6 段（秒 分 时 日 月 周），例如每天 8 点 = 0 0 8 * * ?";
        }
        if (instruction == null || instruction.isBlank()) {
            return "要执行什么还没说清楚。";
        }
        // 列宽 2000：LLM 解析路径会截到 500，面板/接口直连的路径得在这里兜住，否则插入直接撞列长
        String normalizedInstruction = instruction.strip();
        if (normalizedInstruction.length() > 2000) {
            normalizedInstruction = normalizedInstruction.substring(0, 2000);
        }
        long existing = repository.countByUserId(userId);
        if (existing >= maxPerUser) {
            return "定时任务已经有 " + existing + " 个了（上限 " + maxPerUser + " 个），先删掉不用的再加。";
        }

        ScheduledTask task = new ScheduledTask();
        task.setUserId(userId);
        task.setTitle(safeTitle(title, instruction));
        task.setInstruction(normalizedInstruction);
        task.setCron(normalizedCron);
        task.setEnabled(true);
        task.setStatus(ScheduledTask.STATUS_IDLE);
        task.setRunCount(0);
        task.setNextRunAt(ScheduledTaskParseService.nextRun(normalizedCron, zone));
        task.setCreatedAt(LocalDateTime.now(zone));
        task.setUpdatedAt(LocalDateTime.now(zone));
        repository.save(task);
        try {
            schedule(task);
        } catch (SchedulerException exception) {
            log.warn("定时任务调度失败 id={}: {}", task.getId(), exception.toString());
            task.setEnabled(false);
            task.setLastError("调度失败");
            repository.save(task);
            return "任务存下来了，但调度器没接受它（已先置为暂停，稍后可以让我再恢复一次）。";
        }
        userLogService.record(userId, "SCHEDULED_TASK_CREATE",
                Map.of("taskId", task.getId(), "cron", normalizedCron));
        return "好，记下了：「" + task.getTitle() + "」会按 " + describeCron(normalizedCron)
                + " 执行，下次大约在 " + display(task.getNextRunAt()) + "。到点我做完会把结果发给你。";
    }

    public List<ScheduledTask> list(String userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public String listText(String userId) {
        List<ScheduledTask> tasks = list(userId);
        if (tasks.isEmpty()) {
            return "还没有定时任务。想加的话直接说，例如「每天早上 8 点把今天的天气发我」。";
        }
        StringBuilder sb = new StringBuilder("定时任务（").append(tasks.size()).append(" 个）：\n");
        for (ScheduledTask task : tasks) {
            sb.append("#").append(task.getId()).append(" ")
                    .append(Boolean.TRUE.equals(task.getEnabled()) ? "✅" : "⏸")
                    .append(" 「").append(task.getTitle()).append("」")
                    .append(describeCron(task.getCron()))
                    .append("，下次 ").append(display(task.getNextRunAt()));
            if (task.getLastRunAt() != null) {
                sb.append("，上次 ").append(display(task.getLastRunAt()))
                        .append(statusText(task.getStatus()));
            }
            sb.append("\n");
        }
        sb.append("要停掉/恢复或删除，直接说「停掉定时任务 3」「删除定时任务 3」「现在跑一下定时任务 3」。");
        return sb.toString();
    }

    public String setEnabled(String userId, Long taskId, boolean value) {
        ScheduledTask task = requireOwned(userId, taskId);
        if (task == null) {
            return "没找到这个定时任务（ID 可能不对，可以发「查看定时任务」）。";
        }
        task.setEnabled(value);
        task.setUpdatedAt(LocalDateTime.now(zone));
        if (value) {
            task.setNextRunAt(ScheduledTaskParseService.nextRun(task.getCron(), zone));
            repository.save(task);
            try {
                if (!scheduler.checkExists(jobKey(task.getId()))) {
                    schedule(task);
                }
            } catch (SchedulerException exception) {
                // 调度没恢复成功就必须把 enabled 改回 false，否则面板显示"已启用 + 有下次时间"，
                // 但调度器里根本没有这个 job，任务永远不会跑（只有下次重启的 resync 才会自愈）
                log.warn("恢复定时任务调度失败 id={}: {}", task.getId(), exception.toString());
                task.setEnabled(false);
                task.setNextRunAt(null);
                task.setLastError("恢复调度失败");
                task.setUpdatedAt(LocalDateTime.now(zone));
                repository.save(task);
                return "恢复失败：调度器没有接受这个任务，已把它保持为暂停状态，稍后再试一次。";
            }
            return "已恢复「" + task.getTitle() + "」，下次 " + display(task.getNextRunAt()) + "。";
        }
        repository.save(task);
        deleteJob(task.getId());
        return "已暂停「" + task.getTitle() + "」，随时可以说「恢复定时任务 " + task.getId() + "」。";
    }

    public String cancel(String userId, Long taskId) {
        ScheduledTask task = requireOwned(userId, taskId);
        if (task == null) {
            return "没找到这个定时任务（ID 可能不对，可以发「查看定时任务」）。";
        }
        deleteJob(task.getId());
        repository.delete(task);
        userLogService.record(userId, "SCHEDULED_TASK_CANCEL", Map.of("taskId", taskId));
        return "已删除定时任务「" + task.getTitle() + "」。";
    }

    /** 立即执行一次：后台跑（避免嵌套在一次对话里执行，串了日志与工具尾注上下文） */
    public String runNow(String userId, Long taskId) {
        ScheduledTask task = requireOwned(userId, taskId);
        if (task == null) {
            return "没找到这个定时任务。";
        }
        final String title = task.getTitle();
        runner.submit(() -> execute(task, true));
        return "好，「" + title + "」现在就去跑，做完我把结果发给你。";
    }

    // ---------------- 执行 ----------------

    /** 到点执行：跑一遍 Agent，把结果推给用户 */
    public String execute(ScheduledTask task, boolean manual) {
        if (task == null) {
            return "";
        }
        if (!manual && !Boolean.TRUE.equals(task.getEnabled())) {
            return "";
        }
        ScheduledTask current = repository.findById(task.getId()).orElse(null);
        if (current == null) {
            return "";
        }
        String userId = current.getUserId();
        MDC.put("userScope", UserScope.forUser(userId));
        current.setStatus(ScheduledTask.STATUS_RUNNING);
        current.setUpdatedAt(LocalDateTime.now(zone));
        repository.save(current);
        try {
            AgentOrchestrator orchestrator = orchestratorProvider.getIfAvailable();
            if (orchestrator == null) {
                throw new IllegalStateException("Agent 运行时不可用");
            }
            String messageId = "scheduled-" + current.getId() + "-" + UUID.randomUUID();
            String reply = orchestrator.onInboundSync(InboundMessage.text(messageId, userId, current.getInstruction()));
            String text = reply == null || reply.isBlank() ? "（这次没有拿到结果）" : reply.strip();
            // 省略号也要算进 lastResult 的 2000 字符列宽里，否则 MySQL 严格模式会拒绝这条 update（任务会卡在 RUNNING）
            String clipped = text.length() <= resultMaxChars
                    ? text : text.substring(0, resultMaxChars - 1) + "…";

            boolean sent = sendToUser(userId, "⏰ 「" + current.getTitle() + "」\n" + text);
            persistRunResult(current, sent ? ScheduledTask.STATUS_SUCCESS : ScheduledTask.STATUS_FAILED, clipped,
                    sent ? null : "推送未被通道接受（可能是主动消息额度限制）");
            userLogService.record(userId, "SCHEDULED_TASK_RUN",
                    Map.of("taskId", current.getId(), "sent", sent, "manual", manual));
            return "已执行「" + current.getTitle() + "」：" + text;
        } catch (RuntimeException exception) {
            log.warn("定时任务执行失败 id={} user={}: {}", current.getId(), userId, exception.toString());
            // 失败分支不动 lastResult（保留上一次的结果），所以要把快照里的旧值原样带过去
            persistRunResult(current, ScheduledTask.STATUS_FAILED, current.getLastResult(),
                    shorten(exception.getMessage(), 500));
            return "执行失败：" + exception.getMessage();
        } finally {
            MDC.remove("userScope");
        }
    }

    /**
     * 写回这次执行的结果：**只更新执行拥有的那几列**（状态/时间/结果/次数），
     * `enabled / title / instruction / cron` 一律不碰。
     *
     * <p>不能整行 save：Agent 链路可能跑一分钟，期间用户在面板上点「暂停」是写库 + 删 Quartz job，
     * 而整行 save 会把执行前那份旧快照的 {@code enabled=true} 覆盖回去——面板显示"已启用 + 有下次时间"，
     * 实际 job 已经被删、任务永远不会再跑（只有下次重启 resync 才自愈）。改标题/指令/cron 同理会被回滚。
     *
     * <p>行已被删除时定向 UPDATE 影响 0 行，等价于原来"删了就别写回"的保护——而且不会有
     * merge 成 INSERT 把删掉的任务复活的风险。
     */
    private void persistRunResult(ScheduledTask task, String status, String lastResult, String lastError) {
        if (task.getId() == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(zone);
        Integer runCount = (task.getRunCount() == null ? 0 : task.getRunCount()) + 1;
        LocalDateTime nextRunAt = ScheduledTaskParseService.nextRun(task.getCron(), zone);
        int updated = repository.updateRunResult(task.getId(), status, now, lastResult, lastError, runCount,
                nextRunAt, now);
        if (updated == 0) {
            log.info("定时任务已在执行期间被删除，本次结果不再写回 id={}", task.getId());
            return;
        }
        task.setStatus(status);
        task.setLastRunAt(now);
        task.setLastResult(lastResult);
        task.setLastError(lastError);
        task.setRunCount(runCount);
        task.setNextRunAt(nextRunAt);
        task.setUpdatedAt(now);
    }

    /** 投递到用户最近一次说话的通道（通道过期时由 ProactiveDelivery 保守兜底） */
    private boolean sendToUser(String userId, String text) {
        UserProfile profile = profileRepository.findById(userId).orElse(null);
        if (profile == null) {
            log.warn("定时任务没有找到用户资料 user={}", userId);
            return false;
        }
        return ProactiveDelivery.send(channels, profile, userId, text);
    }

    // ---------------- Quartz ----------------

    private void schedule(ScheduledTask task) throws SchedulerException {
        String cron = ScheduledTaskParseService.normalizeCron(task.getCron());
        JobDetail job = JobBuilder.newJob(ScheduledTaskJob.class)
                .withIdentity(jobKey(task.getId()))
                .usingJobData("taskId", task.getId())
                .build();
        CronTrigger trigger = TriggerBuilder.newTrigger()
                .withIdentity("scheduled-trigger-" + task.getId(), GROUP)
                .withSchedule(CronScheduleBuilder.cronSchedule(cron)
                        .inTimeZone(java.util.TimeZone.getTimeZone(zone))
                        .withMisfireHandlingInstructionDoNothing())
                .forJob(job)
                .build();
        if (scheduler.checkExists(jobKey(task.getId()))) {
            scheduler.deleteJob(jobKey(task.getId()));
        }
        scheduler.scheduleJob(job, trigger);
    }

    private void deleteJob(Long taskId) {
        try {
            scheduler.deleteJob(jobKey(taskId));
        } catch (SchedulerException exception) {
            log.warn("删除定时任务调度失败 id={}: {}", taskId, exception.getMessage());
        }
    }

    private void refreshNextRun(ScheduledTask task) {
        LocalDateTime next = ScheduledTaskParseService.nextRun(task.getCron(), zone);
        if (next != null && !next.equals(task.getNextRunAt())) {
            task.setNextRunAt(next);
            repository.save(task);
        }
    }

    /**
     * 容器在「执行中」被重启会留下永远 RUNNING 的行（进程内的执行状态没了，没人再改它），
     * 面板会一直显示"执行中"。启动时统一收口成"结果未知"，与任务状态词典里的 UNKNOWN_RESULT 对齐。
     */
    private void clearStaleRunning(ScheduledTask task) {
        if (!ScheduledTask.STATUS_RUNNING.equals(task.getStatus())) {
            return;
        }
        task.setStatus(ScheduledTask.STATUS_FAILED);
        task.setLastError("执行过程中应用被重启，本次结果未知");
        task.setUpdatedAt(LocalDateTime.now(zone));
        repository.save(task);
        log.info("清理上次重启遗留的执行中状态 id={}", task.getId());
    }

    private JobKey jobKey(Long taskId) {
        return JobKey.jobKey("scheduled-" + taskId, GROUP);
    }

    // ---------------- 参数展示 ----------------

    /** 面板/聊天里展示的下次执行时间（Quartz 触发器是权威，这里兜底算一次） */
    public Map<String, Object> describe(ScheduledTask task) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", task.getId());
        map.put("title", task.getTitle());
        map.put("instruction", task.getInstruction());
        map.put("cron", task.getCron());
        map.put("schedule", describeCron(task.getCron()));
        map.put("enabled", Boolean.TRUE.equals(task.getEnabled()));
        map.put("status", task.getStatus());
        map.put("nextRunAt", display(task.getNextRunAt()));
        map.put("lastRunAt", task.getLastRunAt() == null ? "" : display(task.getLastRunAt()));
        map.put("lastResult", task.getLastResult() == null ? "" : task.getLastResult());
        map.put("lastError", task.getLastError() == null ? "" : task.getLastError());
        map.put("runCount", task.getRunCount() == null ? 0 : task.getRunCount());
        map.put("createdAt", task.getCreatedAt() == null ? "" : display(task.getCreatedAt()));
        return map;
    }

    /** Cron → 人话（只覆盖常见写法，认不出来就原样显示） */
    public static String describeCron(String cron) {
        String value = ScheduledTaskParseService.normalizeCron(cron);
        if (value == null) {
            return "（未设置）";
        }
        String[] parts = value.split("\\s+");
        if (parts.length != 6) {
            return value;
        }
        String second = parts[0];
        String minute = parts[1];
        String hour = parts[2];
        String day = parts[3];
        String month = parts[4];
        String week = parts[5];
        StringBuilder sb = new StringBuilder();
        if ("*".equals(minute) && "*".equals(hour)) {
            sb.append("每分钟");
        } else if (minute.startsWith("*/") && "*".equals(hour)) {
            sb.append("每 ").append(minute.substring(2)).append(" 分钟");
        } else if ("*".equals(hour) && isNumber(minute)) {
            sb.append("每小时的第 ").append(minute).append(" 分");
        } else if (isNumber(hour) && isNumber(minute)) {
            sb.append("每天 ").append(pad(hour)).append(":").append(pad(minute));
        } else if (hour.startsWith("*/") && isNumber(minute)) {
            sb.append("每 ").append(hour.substring(2)).append(" 小时（第 ").append(minute).append(" 分）");
        } else {
            return value;
        }
        if (!"?".equals(week) && !"*".equals(week)) {
            sb.append("，限 ").append(week);
        }
        if (!"*".equals(day) && !"?".equals(day)) {
            sb.append("，日 ").append(day);
        }
        if (!"*".equals(month)) {
            sb.append("，月 ").append(month);
        }
        return sb.toString();
    }

    private static boolean isNumber(String value) {
        return value.matches("\\d{1,2}");
    }

    private static String pad(String value) {
        return value.length() == 1 ? "0" + value : value;
    }

    private String statusText(String status) {
        if (ScheduledTask.STATUS_SUCCESS.equals(status)) {
            return "成功";
        }
        if (ScheduledTask.STATUS_FAILED.equals(status)) {
            return "失败";
        }
        if (ScheduledTask.STATUS_RUNNING.equals(status)) {
            return "执行中";
        }
        return "未执行";
    }

    private String display(LocalDateTime time) {
        return time == null ? "待排期" : time.format(DISPLAY);
    }

    private void requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new BizException("当前用户上下文不存在");
        }
    }

    private ScheduledTask requireOwned(String userId, Long taskId) {
        requireUser(userId);
        if (taskId == null || taskId <= 0) {
            return null;
        }
        return repository.findById(taskId)
                .filter(task -> userId.equals(task.getUserId()))
                .orElse(null);
    }

    private String safeTitle(String title, String instruction) {
        String value = title == null ? "" : title.strip();
        if (value.isBlank()) {
            value = instruction == null ? "定时任务" : instruction.strip();
            value = value.length() > 16 ? value.substring(0, 16) : value;
        }
        return value.length() > 120 ? value.substring(0, 120) : value;
    }

    private static String shorten(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static ZoneId parseZone(String value) {
        try {
            return ZoneId.of(value);
        } catch (RuntimeException ignored) {
            return DEFAULT_ZONE;
        }
    }

    /** 面板/兜底用：把任务自身信息整理成可读列表 */
    public List<Map<String, Object>> describeAll(String userId) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ScheduledTask task : list(userId)) {
            list.add(describe(task));
        }
        return list;
    }
}
