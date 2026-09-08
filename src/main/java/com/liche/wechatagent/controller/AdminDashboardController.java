package com.liche.wechatagent.controller;

import com.liche.wechatagent.agent.AgentTaskStateStore;
import com.liche.wechatagent.agent.AgentOrchestrator;
import com.liche.wechatagent.channel.qq.QqChannel;
import com.liche.wechatagent.log.OperationLog;
import com.liche.wechatagent.log.OperationLogRepository;
import com.liche.wechatagent.memory.ConversationMemoryRepository;
import com.liche.wechatagent.memory.EpisodicMemoryRepository;
import com.liche.wechatagent.memory.UserCoreMemoryRepository;
import com.liche.wechatagent.memory.UserWorkMemoryRepository;
import com.liche.wechatagent.media.StoredMedia;
import com.liche.wechatagent.media.StoredMediaRepository;
import com.liche.wechatagent.reminder.ReminderTaskRepository;
import com.liche.wechatagent.user.UserProfile;
import com.liche.wechatagent.user.UserProfileRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.quartz.Scheduler;
import jakarta.servlet.http.HttpServletRequest;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class AdminDashboardController {
    private final HealthController health;
    private final AgentTaskStateStore tasks;
    private final AgentOrchestrator orchestrator;
    private final UserProfileRepository users;
    private final ConversationMemoryRepository conversations;
    private final EpisodicMemoryRepository episodes;
    private final UserCoreMemoryRepository core;
    private final UserWorkMemoryRepository work;
    private final ReminderTaskRepository reminders;
    private final OperationLogRepository logs;
    private final ObjectProvider<QqChannel> qq;
    private final DashboardMetricHistory history;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final StoredMediaRepository media;
    private final Scheduler scheduler;

    public AdminDashboardController(HealthController health, AgentTaskStateStore tasks, AgentOrchestrator orchestrator,
            UserProfileRepository users, ConversationMemoryRepository conversations,
            EpisodicMemoryRepository episodes, UserCoreMemoryRepository core,
            UserWorkMemoryRepository work, ReminderTaskRepository reminders,
            OperationLogRepository logs, ObjectProvider<QqChannel> qq, JdbcTemplate jdbc, StringRedisTemplate redis, Scheduler scheduler, StoredMediaRepository media, DashboardMetricHistory history) {
        this.history = history;
        this.health=health; this.tasks=tasks; this.orchestrator=orchestrator; this.users=users; this.conversations=conversations;
        this.episodes=episodes; this.core=core; this.work=work; this.reminders=reminders; this.logs=logs; this.qq=qq; this.jdbc=jdbc; this.redis=redis; this.scheduler=scheduler; this.media=media;
    }

    @GetMapping("/overview")
    public Map<String,Object> overview() {
        Map<String,Object> out = new LinkedHashMap<>(health.health());
        Runtime rt = Runtime.getRuntime();
        Map<String,Object> jvm = new LinkedHashMap<>();
        jvm.put("heapUsed", rt.totalMemory()-rt.freeMemory()); jvm.put("heapMax", rt.maxMemory());
        jvm.put("processors", rt.availableProcessors());
        jvm.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        var operatingSystem = ManagementFactory.getOperatingSystemMXBean();
        jvm.put("systemCpuLoad", operatingSystem instanceof com.sun.management.OperatingSystemMXBean extended
                ? extended.getCpuLoad() : -1);
        java.io.File root = new java.io.File(".").getAbsoluteFile();
        jvm.put("diskFree", root.getUsableSpace()); jvm.put("diskTotal", root.getTotalSpace());
        out.put("jvm", jvm);
        Map<String, String> moduleErrors = new LinkedHashMap<>();
        collect(out, moduleErrors, "users", users::count);
        collect(out, moduleErrors, "conversations", conversations::count);
        collect(out, moduleErrors, "episodes", episodes::count);
        collect(out, moduleErrors, "coreMemories", core::count);
        collect(out, moduleErrors, "workMemories", work::count);
        collect(out, moduleErrors, "reminders", reminders::count);
        collect(out, moduleErrors, "tasks", this::taskSummary);
        Map<String, String> dependencies = dependencyHealth();
        out.put("dependencies", dependencies);
        out.put("moduleErrors", moduleErrors);
        List<String> alerts = new ArrayList<>();
        if ("DOWN".equals(out.get("qq"))) alerts.add("QQ 网关已离线");
        if (!tasks.findByStatus("UNKNOWN_RESULT").isEmpty()) alerts.add("存在 UNKNOWN_RESULT 任务");
        dependencies.forEach((name, state) -> {
            if (!"UP".equals(state)) alerts.add(name + " 状态异常：" + state);
        });
        if (!moduleErrors.isEmpty()) alerts.add("部分业务数据暂不可用");
        out.put("status", alerts.isEmpty() ? "UP" : "DEGRADED");
        out.put("alerts", alerts); return out;
    }

    @Scheduled(fixedDelayString="${management.dashboard.metrics-sample-ms:10000}")
    public void scheduledSample() { history.record(overview()); }

    @GetMapping("/metrics/history") public List<Map<String,Object>> history() { return history.snapshot(); }

    @GetMapping("/tasks") public Map<String,Object> taskList(@RequestParam(defaultValue="") String status,
            @RequestParam(defaultValue="") String query,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        List<Map<Object,Object>> all = tasks.findTaskIds().stream().map(id -> {
                    Map<Object,Object> state = new LinkedHashMap<>(tasks.find(id));
                    if (!state.isEmpty()) state.put("taskId", id); return state;
                }).filter(m -> !m.isEmpty())
                .filter(m -> status.isBlank() || status.equals(String.valueOf(m.get("status"))))
                .filter(m -> query.isBlank() || m.toString().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).toList();
        int boundedSize = Math.max(1, Math.min(100, size));
        int from = (int) Math.min((long) Math.max(0, page) * boundedSize, all.size());
        int to = (int) Math.min((long) from + boundedSize, all.size());
        return Map.of("total",all.size(),"items",all.subList(from,to));
    }

    @GetMapping("/users") public List<Map<String,Object>> userList() {
        return users.findAll().stream().map(u -> {
            Map<String,Object> out = new LinkedHashMap<>(); out.put("userId", u.getUserId());
            out.put("lastSeenAt", String.valueOf(u.getLastSeenAt())); out.put("channel", String.valueOf(u.getLastChannel()));
            out.put("createdAt", String.valueOf(u.getCreatedAt())); return out;
        }).toList();
    }

    @GetMapping("/users/{userId}") public Map<String,Object> user(@PathVariable String userId) {
        PageRequest detailPage = PageRequest.of(0, 50);
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("userId", userId); out.put("profile", users.findById(userId).orElse(null));
        out.put("conversations", conversations.findByUserIdOrderByCreatedAtDesc(userId, detailPage));
        out.put("coreMemories", core.findByUserIdOrderByUpdatedAtDesc(userId, detailPage));
        out.put("workMemories", work.findByUserIdOrderByUpdatedAtDesc(userId, detailPage));
        out.put("episodicMemories", episodes.findByUserIdOrderByCreatedAtDesc(userId, detailPage));
        out.put("media", media.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, StoredMedia.ACTIVE, detailPage).stream().map(this::safeMedia).toList());
        out.put("reminders", reminders.findByUserIdOrderByUpdatedAtDesc(userId, detailPage));
        out.put("pageSize", detailPage.getPageSize());
        out.put("truncated", true);
        return out;
    }

    @GetMapping("/logs") public List<OperationLog> logs(@RequestParam(defaultValue="") String level, @RequestParam(defaultValue="") String query) {
        return logs.findTop100ByOrderByCreatedAtDesc().stream().filter(x -> level.isBlank() || x.getAction().toUpperCase(Locale.ROOT).contains(level.toUpperCase(Locale.ROOT)))
                .filter(x -> query.isBlank() || (String.valueOf(x.getDetail())+x.getAction()).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).toList();
    }

    @PostMapping("/actions/qq/reconnect") public Map<String,Object> reconnect(HttpServletRequest request) {
        QqChannel channel=qq.getIfAvailable(); if(channel==null) return Map.of("accepted",false,"message","QQ 未启用");
        channel.requestReconnect(); audit(request,"QQ_RECONNECT","accepted"); return Map.of("accepted",true);
    }
    @PostMapping("/actions/tasks/{taskId}/retry") public Map<String,Object> retry(@PathVariable String taskId, HttpServletRequest request) {
        boolean accepted=orchestrator.retryTask(taskId);
        audit(request,"TASK_RETRY",taskId+" accepted="+accepted); return Map.of("accepted",accepted,"taskId",taskId);
    }
    @PostMapping("/actions/cache/cleanup") public Map<String,Object> cleanup(HttpServletRequest request) {
        QqChannel channel=qq.getIfAvailable(); if(channel!=null) channel.cleanupCaches(); audit(request,"CACHE_CLEANUP","expired-only"); return Map.of("status","done");
    }

    private Map<String,Object> taskSummary() { Map<String,Object> m=new LinkedHashMap<>(); for(String s:List.of("RUNNING","FAILED","UNKNOWN_RESULT","REPLY_SENT")) m.put(s,tasks.findByStatus(s).size()); return m; }
    private void collect(Map<String, Object> output, Map<String, String> errors,
                         String name, java.util.function.Supplier<?> source) {
        try {
            output.put(name, source.get());
        } catch (RuntimeException exception) {
            output.put(name, null);
            errors.put(name, "数据源暂不可用");
        }
    }
    private Map<String,String> dependencyHealth() {
        Map<String,String> result = new LinkedHashMap<>();
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            result.put("mysql", "UP");
        } catch (Exception exception) {
            result.put("mysql", "DOWN");
        }
        try (var connection = redis.getConnectionFactory().getConnection()) {
            result.put("redis", "PONG".equalsIgnoreCase(connection.ping()) ? "UP" : "DOWN");
        } catch (Exception exception) {
            result.put("redis", "DOWN");
        }
        try {
            result.put("quartz", scheduler.isShutdown() ? "DOWN" : scheduler.isInStandbyMode() ? "STANDBY" : scheduler.isStarted() ? "UP" : "DOWN");
        } catch (Exception exception) {
            result.put("quartz", "DOWN");
        }
        return result;
    }
    private String mask(String value) { if(value==null||value.length()<5)return "***"; return value.substring(0,2)+"***"+value.substring(value.length()-2); }
    private void audit(HttpServletRequest request, String action, String detail) { try { logs.save(new OperationLog("admin", action, detail+" ip="+request.getRemoteAddr())); } catch (RuntimeException ignored) {} }
    private Map<String,Object> safeMedia(StoredMedia value) { Map<String,Object> m=new LinkedHashMap<>(); m.put("id",value.getId()); m.put("fileName",value.getFileName()); m.put("contentType",value.getContentType()); m.put("sizeBytes",value.getSizeBytes()); m.put("summary",value.getSummary()); m.put("createdAt",value.getCreatedAt()); return m; }
}
