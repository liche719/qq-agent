package com.liche.wechatagent.schedule;

import com.liche.wechatagent.exam.ExamPushService;
import com.liche.wechatagent.maimemo.MaimemoService;
import com.liche.wechatagent.user.UserProfile;
import com.liche.wechatagent.user.UserProfileRepository;
import org.quartz.CronExpression;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统内置定时任务的清单（面板「定时任务」页用）。
 *
 * <p>内置任务分两种：**Quartz 调度的**（提醒、用户定时任务）能查到真实的下次触发时间；
 * **Spring {@code @Scheduled} 固定周期/定点**的（每日推送、备份、各类扫描）Spring 不暴露下次执行时间，
 * 所以这里只如实展示配置里的频率/时间点，能算出下次执行的（定点与 Cron）就算出来，算不出的标"—"。
 * 目的只有一个：**让用户在面板上看得见系统到底在自动做什么**，而不是靠猜。
 */
@Service
public class BuiltinScheduleService {

    private static final Logger log = LoggerFactory.getLogger(BuiltinScheduleService.class);
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    private final Scheduler scheduler;
    private final MaimemoService maimemoService;
    private final ExamPushService examPushService;
    private final UserProfileRepository profileRepository;
    private final ZoneId zone;

    private final String careDeliveryTime;
    private final String backupCron;
    private final long alertCheckMs;
    private final long careScanMs;
    private final long maimemoPushScanMs;
    private final long reminderRecoveryMs;
    private final long memoryLifecycleMs;
    private final long dashboardSampleMs;

    public BuiltinScheduleService(Scheduler scheduler,
                                  MaimemoService maimemoService,
                                  ExamPushService examPushService,
                                  UserProfileRepository profileRepository,
                                  @Value("${care.delivery-time:20:30}") String careDeliveryTime,
                                  @Value("${backup.cron:0 0 3 * * ?}") String backupCron,
                                  @Value("${alert.check-interval-ms:60000}") long alertCheckMs,
                                  @Value("${care.scan-interval-ms:60000}") long careScanMs,
                                  @Value("${maimemo.push-scan-interval-ms:60000}") long maimemoPushScanMs,
                                  @Value("${reminder.recovery-scan-interval-ms:300000}") long reminderRecoveryMs,
                                  @Value("${memory.lifecycle-scan-interval-ms:3600000}") long memoryLifecycleMs,
                                  @Value("${management.dashboard.metrics-sample-ms:10000}") long dashboardSampleMs,
                                  @Value("${app.time-zone:Asia/Shanghai}") String timeZoneId) {
        this.scheduler = scheduler;
        this.maimemoService = maimemoService;
        this.examPushService = examPushService;
        this.profileRepository = profileRepository;
        this.careDeliveryTime = careDeliveryTime;
        this.backupCron = backupCron;
        this.alertCheckMs = alertCheckMs;
        this.careScanMs = careScanMs;
        this.maimemoPushScanMs = maimemoPushScanMs;
        this.reminderRecoveryMs = reminderRecoveryMs;
        this.memoryLifecycleMs = memoryLifecycleMs;
        this.dashboardSampleMs = dashboardSampleMs;
        this.zone = parseZone(timeZoneId);
    }

    /** 内置任务清单 */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(maimemoPush());
        rows.add(examPush());
        rows.add(proactiveCare());
        rows.add(backup());
        rows.add(quartzGroup("提醒调度（Quartz）", "用户创建的定时提醒，到点推送一句话",
                "reminders", "/reminders 查看清单"));
        rows.add(quartzGroup("定时任务调度（Quartz）", "用户创建的定时任务，到点真正执行并把结果发回",
                "scheduled-tasks", "面板下方「我创建的定时任务」"));
        rows.add(interval("运维告警检查", "QQ 网关、MySQL/Redis/Quartz、磁盘与内存异常时推送告警", alertCheckMs));
        rows.add(interval("主动关怀扫描", "到点给开启了关怀的用户发复盘邀请", careScanMs));
        rows.add(interval("背单词推送扫描", "检查是否到点该发今日背单词进度", maimemoPushScanMs));
        rows.add(interval("提醒恢复扫描", "补齐丢失的提醒调度（重启后自愈）", reminderRecoveryMs));
        rows.add(interval("记忆生命周期扫描", "清理过期上下文与失效记忆", memoryLifecycleMs));
        rows.add(interval("面板指标采样", "给总览页的趋势图取样", dashboardSampleMs));
        rows.sort(Comparator.comparing(row -> String.valueOf(row.get("group"))));
        return rows;
    }

    private Map<String, Object> maimemoPush() {
        Map<String, Object> push = maimemoService.pushState();
        boolean enabled = Boolean.TRUE.equals(push.get("enabled"));
        Map<String, Object> row = base("每日推送", "定时", "墨墨背单词今日进度推送到本人 QQ",
                enabled ? "每天 " + push.get("time") : "已关闭", "面板「背单词」页可改时间/开关");
        row.put("nextRunAt", enabled ? nextDaily(String.valueOf(push.get("time"))) : "—");
        row.put("lastRunText", push.get("lastPushDate") == null || String.valueOf(push.get("lastPushDate")).isBlank()
                ? "还没推过" : "上次推送日期 " + push.get("lastPushDate"));
        row.put("enabled", enabled);
        return row;
    }

    /** 考研模块的三条推送（早计划 / 晚收尾 / 周复盘），时间与开关来自 exam.* */
    private Map<String, Object> examPush() {
        Map<String, Object> push = examPushService.pushState();
        boolean enabled = Boolean.TRUE.equals(push.get("enabled"));
        Map<String, Object> row = base("考研推送", "定时", "今日计划（早）、完成情况（晚）、周复盘（周日）推送给备考用户",
                enabled ? "早 " + push.get("morning") + " · 晚 " + push.get("evening") + " · 周 " + push.get("weekly")
                        : "已关闭", "面板「考研」页可开关；exam_plan 上记「今天已推」标记");
        row.put("nextRunAt", enabled ? nextDaily(String.valueOf(push.get("morning"))) : "—");
        row.put("enabled", enabled);
        return row;
    }

    private Map<String, Object> proactiveCare() {
        // 关怀是按用户排期的：取最近一个到期时间给面板看
        List<UserProfile> caring = profileRepository
                .findByProactiveCareEnabledTrueAndNextCareAtLessThanEqual(LocalDateTime.now(zone).plusYears(5));
        LocalDateTime earliest = caring.stream()
                .map(UserProfile::getNextCareAt)
                .filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
        Map<String, Object> row = base("主动关怀复盘", "定时", "按用户设置每天/每周做一次低打扰复盘",
                "每天 " + careDeliveryTime + "（按用户开关）", "用户在聊天里用 /care 管理");
        row.put("nextRunAt", earliest == null ? "—" : earliest.format(DISPLAY));
        row.put("lastRunText", "已开启的用户：" + caring.size() + " 个");
        row.put("enabled", !caring.isEmpty());
        return row;
    }

    private Map<String, Object> backup() {
        Map<String, Object> row = base("数据库备份", "定时", "导出数据库与变更日志到服务器 backup 目录",
                "Cron " + backupCron, "备份目录与保留天数见 backup.*");
        row.put("nextRunAt", nextCron(backupCron));
        return row;
    }

    private Map<String, Object> quartzGroup(String name, String desc, String group, String manageHint) {
        String next = null;
        int jobs = 0;
        try {
            for (JobKey key : scheduler.getJobKeys(GroupMatcher.jobGroupEquals(group))) {
                jobs++;
                for (Trigger trigger : scheduler.getTriggersOfJob(key)) {
                    Date fire = trigger.getNextFireTime();
                    if (fire == null) {
                        continue;
                    }
                    String text = LocalDateTime.ofInstant(fire.toInstant(), zone).format(DISPLAY);
                    if (next == null || text.compareTo(next) < 0) {
                        next = text;
                    }
                }
            }
        } catch (SchedulerException exception) {
            log.warn("读取 Quartz 任务组失败 group={}: {}", group, exception.getMessage());
        }
        Map<String, Object> row = base(name, "Quartz", desc, "当前 " + jobs + " 个在调度", manageHint);
        row.put("nextRunAt", next == null ? "—" : next);
        row.put("count", jobs);
        return row;
    }

    private Map<String, Object> interval(String name, String desc, long intervalMs) {
        long seconds = Math.max(1, intervalMs / 1000);
        String text = seconds % 3600 == 0 ? "每 " + (seconds / 3600) + " 小时"
                : seconds % 60 == 0 ? "每 " + (seconds / 60) + " 分钟" : "每 " + seconds + " 秒";
        Map<String, Object> row = base(name, "周期", desc, text, "固定周期，Spring 不暴露下次执行时间");
        row.put("nextRunAt", "—");
        return row;
    }

    private Map<String, Object> base(String name, String group, String description, String schedule, String note) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("group", group);
        row.put("description", description);
        row.put("schedule", schedule);
        row.put("note", note);
        row.put("builtin", true);
        return row;
    }

    private String nextDaily(String time) {
        try {
            LocalTime target = LocalTime.parse(String.valueOf(time));
            LocalDateTime now = LocalDateTime.now(zone);
            LocalDateTime candidate = now.toLocalDate().atTime(target);
            if (!candidate.isAfter(now)) {
                candidate = candidate.plusDays(1);
            }
            return candidate.format(DISPLAY);
        } catch (RuntimeException exception) {
            return "—";
        }
    }

    private String nextCron(String cron) {
        LocalDateTime next = ScheduledTaskParseService.nextRun(cron, zone);
        return next == null ? "—" : next.format(DISPLAY);
    }

    private static ZoneId parseZone(String value) {
        try {
            return ZoneId.of(value);
        } catch (RuntimeException ignored) {
            return DEFAULT_ZONE;
        }
    }

    /** Cron 合法性（供面板校验提示用，避免用户填错才报错） */
    public static boolean validCron(String cron) {
        return ScheduledTaskParseService.isValidCron(ScheduledTaskParseService.normalizeCron(cron));
    }
}
