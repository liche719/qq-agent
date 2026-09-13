package com.liche.wechatagent.controller;

import com.liche.wechatagent.config.AlertProperties;
import com.liche.wechatagent.exam.ExamPushService;
import com.liche.wechatagent.exam.ExamService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 考研模块的面板接口（面板「考研」页）。
 *
 * <p>这一组接口的返回形状是**通用描述式面板**的契约（见 {@link AdminPanelController}）：
 * info 区块返回 {@code {"rows":[{"label","value"}]}}，table 区块返回 {@code {"rows":[…]}}，
 * bars 区块返回 {@code {"items":[{"label","value"}]}}。形状对了，前端不用为这个模块写任何代码。
 *
 * <p>归属用户沿用运维告警配置的本人 openid（QQ 私聊里 userId 就是 openid）。
 */
@RestController
@RequestMapping("/api/admin/exam")
public class AdminExamController {

    private final ExamService examService;
    private final ExamPushService pushService;
    private final AlertProperties alertProperties;

    public AdminExamController(ExamService examService, ExamPushService pushService,
                               AlertProperties alertProperties) {
        this.examService = examService;
        this.pushService = pushService;
        this.alertProperties = alertProperties;
    }

    @GetMapping("/plan")
    public Map<String, Object> plan() {
        String userId = ownerUserId();
        return userId.isBlank() ? notConfigured() : examService.planPanel(userId);
    }

    @GetMapping("/tasks")
    public Map<String, Object> tasks(@RequestParam(required = false) String date) {
        String userId = ownerUserId();
        return userId.isBlank() ? notConfigured() : examService.tasksPanel(userId, date);
    }

    @GetMapping("/trend")
    public Map<String, Object> trend(@RequestParam(required = false, defaultValue = "7") int days) {
        String userId = ownerUserId();
        return userId.isBlank() ? notConfiguredBars() : examService.trendPanel(userId, days);
    }

    @GetMapping("/checkins")
    public Map<String, Object> checkins(@RequestParam(required = false, defaultValue = "14") int days) {
        String userId = ownerUserId();
        return userId.isBlank() ? notConfigured() : examService.checkinPanel(userId, days);
    }

    @PostMapping("/tasks/generate")
    public ResponseEntity<Map<String, Object>> generate() {
        String userId = ownerUserId();
        if (userId.isBlank()) {
            return ResponseEntity.ok(notConfiguredMessage());
        }
        int created = examService.generateTodayTasks(userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", true);
        result.put("message", created > 0
                ? "已按计划生成 " + created + " 项今天的任务"
                : "今天已经有任务了，没有重复生成（想重排可以在 QQ 里让我改计划）");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/push")
    public ResponseEntity<Map<String, Object>> push(@RequestBody(required = false) Map<String, String> body) {
        String userId = ownerUserId();
        if (userId.isBlank()) {
            return ResponseEntity.ok(notConfiguredMessage());
        }
        String kind = body == null ? null : body.get("kind");
        Map<String, Object> pushed = pushService.pushNow(userId, kind);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", Boolean.TRUE.equals(pushed.get("sent")));
        result.put("message", pushed.get("message"));
        result.put("text", pushed.get("text"));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/toggle")
    public ResponseEntity<Map<String, Object>> toggle(@RequestBody(required = false) Map<String, Object> body) {
        String userId = ownerUserId();
        if (userId.isBlank()) {
            return ResponseEntity.ok(notConfiguredMessage());
        }
        boolean enabled = body == null || !(body.get("enabled") instanceof Boolean value) || value;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", true);
        result.put("message", examService.setEnabled(userId, enabled));
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> notConfigured() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", java.util.List.of(Map.of("label", "未配置", "value",
                "没有拿到归属用户：请先配置 alert.qq-openid（面板「QQ 通道」页的告警收件人）")));
        return result;
    }

    private Map<String, Object> notConfiguredBars() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", java.util.List.of());
        result.put("unit", "%");
        result.put("message", "没有拿到归属用户：请先配置 alert.qq-openid");
        return result;
    }

    private Map<String, Object> notConfiguredMessage() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", false);
        result.put("message", "没有拿到归属用户：请先配置 alert.qq-openid");
        return result;
    }

    private String ownerUserId() {
        String openid = alertProperties.getQqOpenid();
        return openid == null ? "" : openid.trim();
    }
}
