package com.liche.wechatagent.controller;

import com.liche.wechatagent.channel.qq.QqChannel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** JVM 启动时间（毫秒），用于前端确认当前实例是否为最新启动 */
    private final long startedAtMillis = ManagementFactory.getRuntimeMXBean().getStartTime();
    private final ZoneId zone;
    private final ObjectProvider<QqChannel> qqChannelProvider;

    @org.springframework.beans.factory.annotation.Autowired
    public HealthController(@Value("${app.time-zone:Asia/Shanghai}") String timeZoneId,
                             ObjectProvider<QqChannel> qqChannelProvider) {
        this.zone = parseZone(timeZoneId);
        this.qqChannelProvider = qqChannelProvider;
    }

    public HealthController(@Value("${app.time-zone:Asia/Shanghai}") String timeZoneId) {
        this(timeZoneId, null);
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", "UP");
        map.put("startedAt", Instant.ofEpochMilli(startedAtMillis)
                .atZone(zone).format(FMT));
        map.put("time", LocalDateTime.now(zone).format(FMT));
        if (qqChannelProvider != null) {
            QqChannel qq = qqChannelProvider.getIfAvailable();
            map.put("qq", qq == null ? "DISABLED" : (qq.isGatewayConnected() ? "UP" : "DOWN"));
        }
        return map;
    }

    private ZoneId parseZone(String value) {
        try {
            return ZoneId.of(value);
        } catch (RuntimeException ignored) {
            return DEFAULT_ZONE;
        }
    }
}
