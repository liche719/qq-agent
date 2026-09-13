package com.liche.wechatagent.exam;

import com.liche.wechatagent.channel.ProactiveDelivery;
import com.liche.wechatagent.channel.WeChatChannel;
import com.liche.wechatagent.user.UserProfile;
import com.liche.wechatagent.user.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 考研的三条定时推送：早上给今日计划、晚上问完成情况、周日给周复盘。
 *
 * <p>和墨墨推送同一套路：**扫描 + "今天是否已推过"标记**（标记存在 {@code exam_plan} 上，重启也不会重复推），
 * 推送一律走 {@link ProactiveDelivery}（回到用户最近说话的通道，QQ 主动消息额度由通道自己记账）。
 * 推送是尽力而为：失败只记日志、不写标记，下一次扫描还会再试；面板里的数据才是准的。
 */
@Service
public class ExamPushService {

    private static final Logger log = LoggerFactory.getLogger(ExamPushService.class);

    private final ExamService examService;
    private final ExamPlanRepository plans;
    private final UserProfileRepository profiles;
    private final List<WeChatChannel> channels;
    private final boolean enabled;
    private final LocalTime morningTime;
    private final LocalTime eveningTime;
    private final LocalTime weeklyTime;
    private final DayOfWeek weeklyDay;
    private final ZoneId zone;

    public ExamPushService(ExamService examService,
                           ExamPlanRepository plans,
                           UserProfileRepository profiles,
                           List<WeChatChannel> channels,
                           @Value("${exam.enabled:true}") boolean enabled,
                           @Value("${exam.morning-time:07:30}") String morningTime,
                           @Value("${exam.evening-time:22:00}") String eveningTime,
                           @Value("${exam.weekly-time:21:00}") String weeklyTime,
                           @Value("${exam.weekly-day:SUNDAY}") String weeklyDay,
                           @Value("${app.time-zone:Asia/Shanghai}") String timeZone) {
        this.examService = examService;
        this.plans = plans;
        this.profiles = profiles;
        this.channels = channels;
        this.enabled = enabled;
        this.morningTime = parseTime(morningTime, LocalTime.of(7, 30));
        this.eveningTime = parseTime(eveningTime, LocalTime.of(22, 0));
        this.weeklyTime = parseTime(weeklyTime, LocalTime.of(21, 0));
        this.weeklyDay = parseDay(weeklyDay, DayOfWeek.SUNDAY);
        this.zone = parseZone(timeZone);
    }

    /** 每分钟看一眼：到点且今天还没推过就推。 */
    @Scheduled(fixedDelayString = "${exam.push-scan-interval-ms:60000}",
            initialDelayString = "${exam.push-initial-delay-ms:60000}")
    public void scan() {
        if (!enabled) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now(zone);
            LocalDate today = now.toLocalDate();
            for (ExamPlan plan : plans.findByEnabledTrue()) {
                String userId = plan.getUserId();
                if (userId == null || userId.isBlank()) {
                    continue;
                }
                if (notBefore(now, morningTime) && examService.morningDue(plan, today)) {
                    if (deliver(userId, examService.morningText(userId))) {
                        examService.markPushed(userId, "morning", today);
                    }
                }
                if (notBefore(now, eveningTime) && examService.eveningDue(plan, today)) {
                    if (deliver(userId, examService.eveningText(userId))) {
                        examService.markPushed(userId, "evening", today);
                    }
                }
                if (today.getDayOfWeek() == weeklyDay && notBefore(now, weeklyTime) && examService.weeklyDue(plan, today)) {
                    if (deliver(userId, examService.weeklyText(userId))) {
                        examService.markPushed(userId, "weekly", today);
                    }
                }
            }
        } catch (RuntimeException exception) {
            log.warn("考研推送检查失败：{}", exception.toString());
        }
    }

    /** 面板按钮：立即推一条（不写"今天已推"标记，方便反复调试）。 */
    public Map<String, Object> pushNow(String userId, String kind) {
        String text;
        String label;
        switch (kind == null ? "auto" : kind) {
            case "morning" -> {
                text = examService.morningText(userId);
                label = "早计划";
            }
            case "evening" -> {
                text = examService.eveningText(userId);
                label = "晚收尾";
            }
            case "weekly" -> {
                text = examService.weeklyText(userId);
                label = "周复盘";
            }
            default -> {
                LocalTime now = LocalTime.now(zone);
                if (now.isBefore(morningTime)) {
                    text = examService.morningText(userId);
                    label = "早计划";
                } else if (now.isBefore(eveningTime)) {
                    text = examService.eveningText(userId);
                    label = "晚收尾";
                } else {
                    text = examService.weeklyText(userId);
                    label = "周复盘";
                }
            }
        }
        boolean sent = deliver(userId, text);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sent", sent);
        result.put("kind", label);
        result.put("text", text);
        result.put("message", sent
                ? "已推送「" + label + "」（内容见 text）"
                : "推送失败：可能是通道不接主动消息或 QQ 主动消息额度限制（内容已生成，见 text）");
        return result;
    }

    private boolean deliver(String userId, String text) {
        UserProfile profile = profiles.findById(userId).orElse(null);
        if (profile == null) {
            log.warn("考研推送未发送：找不到用户资料 user={}", userId);
            return false;
        }
        try {
            boolean sent = ProactiveDelivery.send(channels, profile, userId, text);
            if (sent) {
                log.info("已推送考研消息 user={}：{}", userId, text.replace('\n', ' '));
            } else {
                log.warn("考研推送失败 user={}（通道不接受主动消息或额度受限）", userId);
            }
            return sent;
        } catch (RuntimeException exception) {
            log.warn("考研推送异常 user={}：{}", userId, exception.toString());
            return false;
        }
    }

    private boolean notBefore(LocalDateTime now, LocalTime target) {
        return !now.toLocalTime().isBefore(target);
    }

    private static LocalTime parseTime(String value, LocalTime fallback) {
        try {
            return value == null || value.isBlank() ? fallback : LocalTime.parse(value.trim());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static DayOfWeek parseDay(String value, DayOfWeek fallback) {
        try {
            return value == null || value.isBlank() ? fallback : DayOfWeek.valueOf(value.trim().toUpperCase());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static ZoneId parseZone(String value) {
        try {
            return value == null || value.isBlank() ? ZoneId.of("Asia/Shanghai") : ZoneId.of(value.trim());
        } catch (RuntimeException ignored) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    /** 面板「定时任务」页要显示的配置（只读） */
    public Map<String, Object> pushState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("enabled", enabled);
        state.put("morning", morningTime.toString());
        state.put("evening", eveningTime.toString());
        state.put("weekly", weeklyDay + " " + weeklyTime);
        return state;
    }
}
