package com.liche.wechatagent.controller;

import com.liche.wechatagent.channel.qq.QqChannel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查与面板遥测。
 *
 * <p>两者**故意分开**：`/api/health` 是公网免口令的，只回答"服务活着吗、现在几点（服务器时区）"；
 * 通道遥测（网关状态、发送计数、连接时间、最近一次 API 错误）与启动时间只通过
 * {@link #dashboardTelemetry()} 提供给带口令的 `/api/admin/overview`
 * ——原来它们混在 `/api/health` 里，匿名访问就能看出机器人在不在线、重启过几次、失败分布。
 */
@RestController
public class HealthController {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** JVM 启动时间（毫秒），面板用来确认当前实例是不是最新启动的那个 */
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
        map.put("time", LocalDateTime.now(zone).format(FMT));
        return map;
    }

    /** 面板总览要用的遥测（带口令才能取到）：服务时间 + 启动时间 + QQ 网关状态与计数 */
    public Map<String, Object> dashboardTelemetry() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("time", LocalDateTime.now(zone).format(FMT));
        map.put("startedAt", Instant.ofEpochMilli(startedAtMillis).atZone(zone).format(FMT));
        if (qqChannelProvider != null) {
            QqChannel qq = qqChannelProvider.getIfAvailable();
            map.put("qq", qq == null ? "DISABLED" : (qq.isGatewayConnected() ? "UP" : "DOWN"));
            if (qq != null) {
                map.put("qqMetrics", qq.healthSnapshot());
            }
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
