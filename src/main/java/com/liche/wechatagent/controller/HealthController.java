package com.liche.wechatagent.controller;

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

@RestController
public class HealthController {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** JVM 启动时间（毫秒），用于前端确认当前实例是否为最新启动 */
    private final long startedAtMillis = ManagementFactory.getRuntimeMXBean().getStartTime();

    @Value("${wechat.channel.mode}")
    private String channelMode;

    @Value("${llm.api-key}")
    private String apiKey;

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", "UP");
        map.put("startedAt", Instant.ofEpochMilli(startedAtMillis)
                .atZone(BEIJING).format(FMT));
        map.put("time", LocalDateTime.now().toString());
        map.put("channelMode", channelMode);
        map.put("llmConfigured", apiKey != null && !apiKey.isBlank());
        return map;
    }
}
