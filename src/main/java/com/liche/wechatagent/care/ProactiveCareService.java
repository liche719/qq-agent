package com.liche.wechatagent.care;

import com.liche.wechatagent.channel.WeChatChannel;
import com.liche.wechatagent.log.UserLogService;
import com.liche.wechatagent.memory.UserWorkMemory;
import com.liche.wechatagent.memory.WorkMemoryService;
import com.liche.wechatagent.user.UserProfile;
import com.liche.wechatagent.user.UserProfileRepository;
import com.liche.wechatagent.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

@Service
public class ProactiveCareService {

    public static final String DAILY = "DAILY";
    public static final String WEEKLY = "WEEKLY";
    private static final Logger log = LoggerFactory.getLogger(ProactiveCareService.class);
    private static final LocalTime DELIVERY_TIME = LocalTime.of(20, 30);
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final UserProfileRepository profileRepository;
    private final UserService userService;
    private final WorkMemoryService workMemoryService;
    private final List<WeChatChannel> channels;
    private final UserLogService userLogService;

    public ProactiveCareService(UserProfileRepository profileRepository,
                                UserService userService,
                                WorkMemoryService workMemoryService,
                                List<WeChatChannel> channels,
                                UserLogService userLogService) {
        this.profileRepository = profileRepository;
        this.userService = userService;
        this.workMemoryService = workMemoryService;
        this.channels = channels;
        this.userLogService = userLogService;
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
        LocalDateTime next = nextDelivery(cadence, LocalDateTime.now());
        userService.configureProactiveCare(userId, true, cadence, next);
        return "主动关怀已开启：" + cadenceText(cadence) + "晚上 20:30 左右，我会围绕你的长期目标做一次简短复盘。下次预计 "
                + next.format(DISPLAY_TIME) + "。随时可用 /care off 关闭。";
    }

    @Scheduled(fixedDelayString = "${care.scan-interval-ms:60000}", initialDelayString = "${care.initial-delay-ms:30000}")
    public void sendDueCheckIns() {
        LocalDateTime now = LocalDateTime.now();
        for (UserProfile profile : profileRepository.findByProactiveCareEnabledTrueAndNextCareAtLessThanEqual(now)) {
            try {
                sendOne(profile, now);
            } catch (Exception exception) {
                log.warn("主动关怀发送失败 user={}: {}", profile.getUserId(), exception.getMessage());
                profile.setNextCareAt(now.plusHours(1));
                profileRepository.save(profile);
            }
        }
    }

    private void sendOne(UserProfile profile, LocalDateTime now) {
        String userId = profile.getUserId();
        if (userId.startsWith("qq-group:")) {
            profile.setProactiveCareEnabled(false);
            profile.setNextCareAt(null);
            profileRepository.save(profile);
            return;
        }
        String focus = findFocus(userId);
        if (focus == null) {
            advance(profile, now, false);
            return;
        }
        WeChatChannel channel = channels.stream()
                .filter(candidate -> candidate.channel().equals(profile.getLastChannel()))
                .findFirst().orElse(null);
        if (channel == null) {
            advance(profile, now, false);
            return;
        }
        String shortened = focus.length() > 100 ? focus.substring(0, 100) + "…" : focus;
        channel.sendTextFrom(profile.getLastBotId(), userId,
                "来做个很短的近况复盘吧。你之前提到「" + shortened
                        + "」，最近推进得怎么样？有卡住的地方就直接告诉我，我陪你一起拆。若不想收到这类消息，发送 /care off 即可。");
        advance(profile, now, true);
        userLogService.record(userId, "PROACTIVE_CARE_PUSH", "focus=" + shortened);
    }

    private String findFocus(String userId) {
        return workMemoryService.listActive(userId).stream()
                .filter(memory -> memory.getPriority() != null && memory.getPriority() >= 4)
                .max(Comparator.comparing(UserWorkMemory::getPriority)
                        .thenComparing(UserWorkMemory::getUpdatedAt))
                .map(UserWorkMemory::getContent)
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
            return now.toLocalDate().plusDays(1).atTime(DELIVERY_TIME);
        }
        return now.toLocalDate()
                .with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.SUNDAY))
                .atTime(DELIVERY_TIME);
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
}
