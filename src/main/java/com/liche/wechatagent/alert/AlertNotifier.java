package com.liche.wechatagent.alert;

import com.liche.wechatagent.channel.qq.QqChannel;
import com.liche.wechatagent.config.AlertProperties;
import org.quartz.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运维告警推送：定期检查 QQ 网关、依赖组件、磁盘与内存，
 * 出现新问题或问题恢复时给管理员本人的 QQ 发一条私聊消息。
 *
 * <p>只推给 {@code alert.qq-openid} 指定的用户，不会推给其它用户；
 * 同一问题在 {@code alert.repeat-minutes} 内不重复推送。
 */
@Component
public class AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(AlertNotifier.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final AlertProperties properties;
    private final ObjectProvider<QqChannel> qqChannel;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final Scheduler scheduler;
    private final ZoneId zone;

    /** 当前处于激活状态的问题：key → 描述 */
    private final Map<String, String> active = new ConcurrentHashMap<>();
    /** 每个问题上次推送时间 */
    private final Map<String, Long> sentAt = new ConcurrentHashMap<>();
    /** 应用启动时刻，用于跳过启动初期的误报 */
    private final long startedAt = System.currentTimeMillis();

    public AlertNotifier(AlertProperties properties, ObjectProvider<QqChannel> qqChannel, JdbcTemplate jdbc,
                         StringRedisTemplate redis, Scheduler scheduler,
                         @Value("${app.time-zone:Asia/Shanghai}") String timeZone) {
        this.properties = properties;
        this.qqChannel = qqChannel;
        this.jdbc = jdbc;
        this.redis = redis;
        this.scheduler = scheduler;
        this.zone = parseZone(timeZone);
    }

    @Scheduled(fixedDelayString = "${alert.check-interval-ms:60000}")
    public void scheduledCheck() {
        if (!usable()) {
            return;
        }
        // 启动宽限期：重启后网关/依赖才陆续就绪，这段时间不判定为故障
        if (System.currentTimeMillis() - startedAt < properties.getStartupGraceSeconds() * 1000L) {
            return;
        }
        inspect();
    }

    /** 立即检查一次，返回本次推送的问题 key */
    public List<String> inspect() {
        if (!usable()) {
            return List.of();
        }
        Map<String, String> problems = collectProblems();
        long now = System.currentTimeMillis();
        long repeat = properties.getRepeatMinutes() * 60_000L;
        List<String> pushed = new ArrayList<>();

        for (Map.Entry<String, String> problem : problems.entrySet()) {
            boolean isNew = !active.containsKey(problem.getKey());
            Long last = sentAt.get(problem.getKey());
            if (isNew || last == null || now - last >= repeat) {
                if (deliver("【运维告警】" + problem.getValue() + "\n时间：" + now())) {
                    sentAt.put(problem.getKey(), now);
                    pushed.add(problem.getKey());
                }
            }
        }
        for (Map.Entry<String, String> entry : new LinkedHashMap<>(active).entrySet()) {
            if (!problems.containsKey(entry.getKey())) {
                deliver("【运维恢复】" + entry.getValue() + " 已恢复\n时间：" + now());
                sentAt.remove(entry.getKey());
            }
        }
        active.keySet().retainAll(problems.keySet());
        active.putAll(problems);
        return pushed;
    }

    /** 面板上的“发送测试告警” */
    public boolean sendTest() {
        if (!usable()) {
            return false;
        }
        return deliver("【运维告警】测试消息：收到这条说明告警推送已生效。\n时间：" + now());
    }

    public boolean ready() {
        return usable();
    }

    private boolean usable() {
        return properties.isEnabled() && !properties.getQqOpenid().isBlank();
    }

    private Map<String, String> collectProblems() {
        Map<String, String> problems = new LinkedHashMap<>();

        QqChannel qq = qqChannel.getIfAvailable();
        if (qq == null) {
            problems.put("qq-disabled", "QQ 通道未启用");
        } else if (!qq.isGatewayConnected()) {
            problems.put("qq-down", "QQ 网关已断开");
        }

        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
        } catch (RuntimeException exception) {
            problems.put("mysql", "MySQL 不可用");
        }

        try (var connection = redis.getConnectionFactory().getConnection()) {
            if (!"PONG".equalsIgnoreCase(connection.ping())) {
                problems.put("redis", "Redis 响应异常");
            }
        } catch (RuntimeException exception) {
            problems.put("redis", "Redis 不可用");
        }

        try {
            if (scheduler.isShutdown() || scheduler.isInStandbyMode() || !scheduler.isStarted()) {
                problems.put("quartz", "调度器状态异常");
            }
        } catch (Exception exception) {
            problems.put("quartz", "调度器不可用");
        }

        long free = new File(".").getUsableSpace();
        if (free > 0 && free < properties.getDiskFreeMinBytes()) {
            problems.put("disk", "磁盘可用空间不足：" + human(free));
        }

        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        long used = runtime.totalMemory() - runtime.freeMemory();
        int percent = max > 0 ? (int) (used * 100 / max) : 0;
        if (percent >= properties.getHeapUsedMaxPercent()) {
            problems.put("heap", "堆内存占用过高：" + percent + "%");
        }
        return problems;
    }

    private boolean deliver(String text) {
        QqChannel qq = qqChannel.getIfAvailable();
        if (qq == null) {
            log.warn("告警未发送（QQ 通道未启用）：{}", text.replace('\n', ' '));
            return false;
        }
        try {
            boolean sent = qq.sendTextResultFrom(null, properties.getQqOpenid(), text);
            if (sent) {
                log.info("已推送运维告警：{}", text.replace('\n', ' '));
            } else {
                log.warn("运维告警推送失败（可能是 QQ 主动消息额度限制）：{}", text.replace('\n', ' '));
            }
            return sent;
        } catch (RuntimeException exception) {
            log.warn("运维告警推送异常：{}", exception.toString());
            return false;
        }
    }

    private String now() {
        return LocalDateTime.now(zone).format(TIME);
    }

    private static String human(long bytes) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double size = bytes;
        int index = 0;
        while (size >= 1024 && index < units.length - 1) {
            size /= 1024;
            index++;
        }
        return String.format("%.1f %s", size, units[index]);
    }

    private static ZoneId parseZone(String value) {
        try {
            return ZoneId.of(value);
        } catch (RuntimeException ignored) {
            return ZoneId.of("Asia/Shanghai");
        }
    }
}
