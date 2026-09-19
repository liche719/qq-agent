package com.liche.wechatagent.care;

import com.liche.wechatagent.channel.ProactiveDelivery;
import com.liche.wechatagent.channel.WeChatChannel;
import com.liche.wechatagent.log.UserLogService;
import com.liche.wechatagent.memory.Memory;
import com.liche.wechatagent.memory.MemoryService;
import com.liche.wechatagent.user.UserProfile;
import com.liche.wechatagent.user.UserProfileRepository;
import com.liche.wechatagent.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class ProactiveCareService {

    public static final String DAILY = "DAILY";
    public static final String WEEKLY = "WEEKLY";
    private static final int DEFAULT_FOCUS_MAX_CHARS = 100;
    private static final int DEFAULT_MIN_FOCUS_PRIORITY = 4;
    private static final int DEFAULT_RETRY_DELAY_MINUTES = 60;
    private static final DayOfWeek DEFAULT_WEEKLY_DAY = DayOfWeek.SUNDAY;
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Logger log = LoggerFactory.getLogger(ProactiveCareService.class);
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final UserProfileRepository profileRepository;
    private final UserService userService;
    private final MemoryService memoryService;
    private final List<WeChatChannel> channels;
    private final UserLogService userLogService;
    private final LocalTime deliveryTime;
    private final int focusMaxChars;
    private final int minFocusPriority;
    private final int retryDelayMinutes;
    private final DayOfWeek weeklyDay;
    private final ZoneId zone;

    @Autowired
    public ProactiveCareService(UserProfileRepository profileRepository,
                                UserService userService,
                                MemoryService memoryService,
                                List<WeChatChannel> channels,
                                UserLogService userLogService,
                                @org.springframework.beans.factory.annotation.Value("${care.delivery-time:20:30}") String deliveryTime,
                                @org.springframework.beans.factory.annotation.Value("${care.focus-max-chars:100}") int focusMaxChars,
                                @org.springframework.beans.factory.annotation.Value("${care.min-focus-priority:4}") int minFocusPriority,
                                @org.springframework.beans.factory.annotation.Value("${care.retry-delay-minutes:60}") int retryDelayMinutes,
                                @org.springframework.beans.factory.annotation.Value("${care.weekly-day:SUNDAY}") String weeklyDay,
                                @org.springframework.beans.factory.annotation.Value("${app.time-zone:Asia/Shanghai}") String timeZoneId) {
        this.profileRepository = profileRepository;
        this.userService = userService;
        this.memoryService = memoryService;
        this.channels = channels;
        this.userLogService = userLogService;
        this.deliveryTime = parseDeliveryTime(deliveryTime);
        this.focusMaxChars = Math.max(20, Math.min(1_000, focusMaxChars));
        this.minFocusPriority = Math.max(1, Math.min(5, minFocusPriority));
        this.retryDelayMinutes = Math.max(1, Math.min(24 * 60, retryDelayMinutes));
        this.weeklyDay = parseWeeklyDay(weeklyDay);
        this.zone = parseZone(timeZoneId);
    }

    ProactiveCareService(UserProfileRepository profileRepository,
                         UserService userService,
                         MemoryService memoryService,
                         List<WeChatChannel> channels,
                         UserLogService userLogService) {
        this(profileRepository, userService, memoryService, channels, userLogService, "20:30",
                DEFAULT_FOCUS_MAX_CHARS, DEFAULT_MIN_FOCUS_PRIORITY, DEFAULT_RETRY_DELAY_MINUTES,
                DEFAULT_WEEKLY_DAY.name(), DEFAULT_ZONE.getId());
    }

    ProactiveCareService(UserProfileRepository profileRepository,
                         UserService userService,
                         MemoryService memoryService,
                         List<WeChatChannel> channels,
                         UserLogService userLogService,
                         String deliveryTime) {
        this(profileRepository, userService, memoryService, channels, userLogService, deliveryTime,
                DEFAULT_FOCUS_MAX_CHARS, DEFAULT_MIN_FOCUS_PRIORITY, DEFAULT_RETRY_DELAY_MINUTES,
                DEFAULT_WEEKLY_DAY.name(), DEFAULT_ZONE.getId());
    }

    ProactiveCareService(UserProfileRepository profileRepository,
                         UserService userService,
                         MemoryService memoryService,
                         List<WeChatChannel> channels,
                         UserLogService userLogService,
                         String deliveryTime,
                         String timeZoneId) {
        this(profileRepository, userService, memoryService, channels, userLogService, deliveryTime,
                DEFAULT_FOCUS_MAX_CHARS, DEFAULT_MIN_FOCUS_PRIORITY, DEFAULT_RETRY_DELAY_MINUTES,
                DEFAULT_WEEKLY_DAY.name(), timeZoneId);
    }

    public String configure(String userId, String args) {
        String value = args == null ? "" : args.trim().toLowerCase();
        UserProfile current = userService.get(userId);
        if (value.isBlank() || "status".equals(value) || "状态".equals(value)) {
            return status(current);
        }
        if ("off".equals(value) || "关闭".equals(value)) {
            userService.configureProactiveCare(userId, false, null, null);
            return "主动关怀已关闭。我仍会正常记住你的长期目标，但不会主动发复盘消息。";
        }
        String cadence;
        if ("daily".equals(value) || "每天".equals(value)) {
            cadence = DAILY;
        } else if ("weekly".equals(value) || "每周".equals(value) || "on".equals(value) || "开启".equals(value)) {
            cadence = WEEKLY;
        } else {
            return "用法：/care on 开启每周复盘；/care daily 每天；/care weekly 每周；/care off 关闭。";
        }
        LocalDateTime next = nextDelivery(cadence, now());
        userService.configureProactiveCare(userId, true, cadence, next);
        return "主动关怀已开启：" + cadenceText(cadence) + "在 " + deliveryTime + " 左右，我会围绕你的长期目标做一次简短复盘。下次预计 "
                + next.format(DISPLAY_TIME) + "。随时可用 /care off 关闭。";
    }

    @Scheduled(fixedDelayString = "${care.scan-interval-ms:60000}", initialDelayString = "${care.initial-delay-ms:30000}")
    public void sendDueCheckIns() {
        LocalDateTime now = now();
        for (UserProfile profile : profileRepository.findByProactiveCareEnabledTrueAndNextCareAtLessThanEqual(now)) {
            try {
                sendOne(profile, now);
            } catch (Exception exception) {
                log.warn("主动关怀发送失败 user={}: {}", profile.getUserId(), exception.getMessage());
                profile.setNextCareAt(now.plusMinutes(retryDelayMinutes));
                profileRepository.save(profile);
            }
        }
    }

    private void sendOne(UserProfile profile, LocalDateTime now) {
        String userId = profile.getUserId();
        String focus = findFocus(userId);
        if (focus == null) {
            advance(profile, now, false);
            return;
        }
        String shortened = focus.length() > focusMaxChars ? focus.substring(0, focusMaxChars) + "…" : focus;
        String message = "来做个很短的近况复盘吧。你之前提到「" + shortened
                + "」，最近推进得怎么样？有卡住的地方就直接告诉我，我陪你一起拆。若不想收到这类消息，发送 /care off 即可。";
        if (!ProactiveDelivery.send(channels, profile, userId, message)) {
            advance(profile, now, false);
            return;
        }
        advance(profile, now, true);
        userLogService.record(userId, "PROACTIVE_CARE_PUSH", Map.of("focusLength", shortened.length()));
    }

    private String findFocus(String userId) {
        return memoryService.listActive(userId, Memory.KIND_TASK).stream()
                .filter(memory -> memory.getPriority() != null && memory.getPriority() >= minFocusPriority)
                .max(Comparator.comparing(Memory::getPriority)
                        .thenComparing(Memory::getUpdatedAt))
                .map(Memory::getContent)
                .orElse(null);
    }

    private void advance(UserProfile profile, LocalDateTime now, boolean sent) {
        String cadence = DAILY.equals(profile.getProactiveCareCadence()) ? DAILY : WEEKLY;
        profile.setNextCareAt(nextDelivery(cadence, now));
        if (sent) profile.setLastCareAt(now);
        profile.setUpdatedAt(now);
        profileRepository.save(profile);
    }

    private LocalDateTime nextDelivery(String cadence, LocalDateTime now) {
        if (DAILY.equals(cadence)) {
            return now.toLocalDate().plusDays(1).atTime(deliveryTime);
        }
        return now.toLocalDate()
                .with(java.time.temporal.TemporalAdjusters.next(weeklyDay))
                .atTime(deliveryTime);
    }

    private String status(UserProfile profile) {
        if (!Boolean.TRUE.equals(profile.getProactiveCareEnabled())) {
            return "主动关怀目前关闭。发送 /care on 可开启每周一次的低打扰目标复盘。";
        }
        return "主动关怀已开启，频率是" + cadenceText(profile.getProactiveCareCadence())
                + "；下次预计 " + (profile.getNextCareAt() == null ? "待安排" : profile.getNextCareAt().format(DISPLAY_TIME))
                + "。发送 /care off 可关闭。";
    }

    private String cadenceText(String cadence) {
        return DAILY.equals(cadence) ? "每天" : "每周";
    }

    private LocalTime parseDeliveryTime(String value) {
        try {
            return LocalTime.parse(value);
        } catch (RuntimeException ignored) {
            return LocalTime.of(20, 30);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(zone);
    }

    private ZoneId parseZone(String value) {
        try {
            return ZoneId.of(value);
        } catch (RuntimeException ignored) {
            return DEFAULT_ZONE;
        }
    }

    private DayOfWeek parseWeeklyDay(String value) {
        try {
            return DayOfWeek.valueOf(value == null ? "" : value.trim().toUpperCase());
        } catch (RuntimeException ignored) {
            return DEFAULT_WEEKLY_DAY;
        }
    }
}
