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
import java.util.concurrent.ConcurrentLinkedDeque;

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
    private final Deque<Map<String,Object>> history = new ConcurrentLinkedDeque<>();
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final Scheduler scheduler;

    public AdminDashboardController(HealthController health, AgentTaskStateStore tasks, AgentOrchestrator orchestrator,
            UserProfileRepository users, ConversationMemoryRepository conversations,
            EpisodicMemoryRepository episodes, UserCoreMemoryRepository core,
            UserWorkMemoryRepository work, ReminderTaskRepository reminders,
            OperationLogRepository logs, ObjectProvider<QqChannel> qq, JdbcTemplate jdbc, StringRedisTemplate redis, Scheduler scheduler) {
        this.health=health; this.tasks=tasks; this.orchestrator=orchestrator; this.users=users; this.conversations=conversations;
        this.episodes=episodes; this.core=core; this.work=work; this.reminders=reminders; this.logs=logs; this.qq=qq; this.jdbc=jdbc; this.redis=redis; this.scheduler=scheduler;
    }

    @GetMapping("/overview")
    public Map<String,Object> overview() {
        Map<String,Object> out = new LinkedHashMap<>(health.health());
        Runtime rt = Runtime.getRuntime();
        Map<String,Object> jvm = new LinkedHashMap<>();
        jvm.put("heapUsed", rt.totalMemory()-rt.freeMemory()); jvm.put("heapMax", rt.maxMemory());
        jvm.put("processors", rt.availableProcessors());
        jvm.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        jvm.put("systemCpuLoad", ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
        java.io.File root = java.io.File.listRoots()[0]; jvm.put("diskFree", root.getFreeSpace()); jvm.put("diskTotal", root.getTotalSpace());
        out.put("jvm", jvm); out.put("users", users.count()); out.put("conversations", conversations.count());
        out.put("episodes", episodes.count()); out.put("coreMemories", core.count()); out.put("workMemories", work.count());
        out.put("reminders", reminders.count()); out.put("tasks", taskSummary());
        out.put("dependencies", dependencyHealth());
        List<String> alerts = new ArrayList<>();
        if ("DOWN".equals(out.get("qq"))) alerts.add("QQ 网关已离线");
        if (!tasks.findByStatus("UNKNOWN_RESULT").isEmpty()) alerts.add("存在 UNKNOWN_RESULT 任务");
        out.put("alerts", alerts); sample(out); return out;
    }

    @Scheduled(fixedDelayString="${management.dashboard.metrics-sample-ms:10000}")
    public void scheduledSample() { overview(); }

    @GetMapping("/metrics/history") public List<Map<String,Object>> history() { return List.copyOf(history); }

    @GetMapping("/tasks") public Map<String,Object> taskList(@RequestParam(defaultValue="") String status,
            @RequestParam(defaultValue="") String query,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        List<Map<Object,Object>> all = tasks.findTaskIds().stream().map(tasks::find).filter(m -> !m.isEmpty())
                .filter(m -> status.isBlank() || status.equals(String.valueOf(m.get("status"))))
                .filter(m -> query.isBlank() || m.toString().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).toList();
        int from=Math.min(Math.max(0,page)*Math.max(1,size),all.size()), to=Math.min(from+Math.max(1,size),all.size());
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
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("userId", userId); out.put("profile", users.findById(userId).orElse(null));
        out.put("conversations", conversations.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0,50)));
        out.put("reminders", reminders.findByUserIdOrderByUpdatedAtDesc(userId)); return out;
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
        Map<Object,Object> state=tasks.claimManualRetry(taskId); boolean accepted=!state.isEmpty() && orchestrator.retryTask(taskId);
        audit(request,"TASK_RETRY",taskId+" accepted="+accepted); return Map.of("accepted",accepted,"taskId",taskId);
    }
    @PostMapping("/actions/cache/cleanup") public Map<String,Object> cleanup(HttpServletRequest request) {
        QqChannel channel=qq.getIfAvailable(); if(channel!=null) channel.cleanupCaches(); audit(request,"CACHE_CLEANUP","expired-only"); return Map.of("status","done");
    }

    private Map<String,Object> taskSummary() { Map<String,Object> m=new LinkedHashMap<>(); for(String s:List.of("RUNNING","FAILED","UNKNOWN_RESULT","REPLY_SENT")) m.put(s,tasks.findByStatus(s).size()); return m; }
    private Map<String,String> dependencyHealth() { Map<String,String> m=new LinkedHashMap<>(); try { jdbc.queryForObject("SELECT 1", Integer.class); m.put("mysql","UP"); } catch(Exception e){m.put("mysql","DOWN");} try { redis.getConnectionFactory().getConnection().ping(); m.put("redis","UP"); } catch(Exception e){m.put("redis","DOWN");} return m; }
    private void sample(Map<String,Object> overview) { Map<String,Object> s=new LinkedHashMap<>(); s.put("at", Instant.now().toString()); s.put("qq",overview.get("qq")); s.put("users",overview.get("users")); s.put("tasks",overview.get("tasks")); s.put("jvm",overview.get("jvm")); history.addLast(s); while(history.size()>360) history.pollFirst(); }
    private String mask(String value) { if(value==null||value.length()<5)return "***"; return value.substring(0,2)+"***"+value.substring(value.length()-2); }
    private void audit(HttpServletRequest request, String action, String detail) { try { logs.save(new OperationLog("admin", action, detail+" ip="+request.getRemoteAddr())); } catch (RuntimeException ignored) {} }
}
