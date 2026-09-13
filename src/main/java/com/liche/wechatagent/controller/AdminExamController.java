package com.liche.wechatagent.controller;

import com.liche.wechatagent.config.AlertProperties;
import com.liche.wechatagent.exam.ExamPlan;
import com.liche.wechatagent.exam.ExamPushService;
import com.liche.wechatagent.exam.ExamService;
import com.liche.wechatagent.exam.ExamTrackService;
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
    private final ExamTrackService trackService;
    private final AlertProperties alertProperties;

    public AdminExamController(ExamService examService, ExamPushService pushService,
                               ExamTrackService trackService, AlertProperties alertProperties) {
        this.examService = examService;
        this.pushService = pushService;
        this.trackService = trackService;
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

    // ==================== 面板表单：计划可编辑 ====================

    /** 表单预填：把库里存的计划还原成可编辑的字段（subjects 还原成「名字:目标分:分钟:计划」文本） */
    @GetMapping("/plan/form")
    public Map<String, Object> planForm() {
        String userId = ownerUserId();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("examDate", "");
        values.put("school", "");
        values.put("major", "");
        values.put("stage", ExamPlan.STAGE_BASIC);
        values.put("dailyMinutes", "");
        values.put("subjects", "");
        values.put("remark", "");
        if (userId.isBlank()) {
            return formResponse(values, "没有拿到归属用户：请先配置 alert.qq-openid");
        }
        ExamPlan plan = examService.plan(userId);
        if (plan != null) {
            values.put("examDate", plan.getExamDate() == null ? "" : plan.getExamDate().toString());
            values.put("school", plan.getSchool() == null ? "" : plan.getSchool());
            values.put("major", plan.getMajor() == null ? "" : plan.getMajor());
            values.put("stage", plan.getStage() == null ? ExamPlan.STAGE_BASIC : plan.getStage());
            values.put("dailyMinutes", plan.getDailyMinutes() == null ? "" : String.valueOf(plan.getDailyMinutes()));
            values.put("subjects", examService.subjectsText(userId));
            values.put("remark", plan.getRemark() == null ? "" : plan.getRemark());
        }
        return formResponse(values, null);
    }

    /** 表单提交：保存计划（和聊天里让模型调 saveExamPlan 是同一条路径） */
    @PostMapping("/plan")
    public ResponseEntity<Map<String, Object>> savePlan(@RequestBody(required = false) Map<String, String> body) {
        String userId = ownerUserId();
        if (userId.isBlank()) {
            return ResponseEntity.ok(notConfiguredMessage());
        }
        Map<String, String> payload = body == null ? Map.of() : body;
        String message = examService.savePlan(userId, value(payload, "examDate"), value(payload, "school"),
                value(payload, "major"), value(payload, "stage"), number(payload.get("dailyMinutes")),
                value(payload, "subjects"), value(payload, "remark"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", !message.contains("没保存") && !message.contains("没看懂") && !message.contains("拿不到"));
        result.put("message", message.length() > 400 ? message.substring(0, 399) + "…" : message);
        return ResponseEntity.ok(result);
    }

    // ==================== 章节/轮次进度 ====================

    @GetMapping("/progress")
    public Map<String, Object> progress() {
        String userId = ownerUserId();
        return userId.isBlank() ? notConfigured() : trackService.progressPanel(userId);
    }

    @GetMapping("/progress/groups")
    public Map<String, Object> progressGroups() {
        String userId = ownerUserId();
        if (userId.isBlank()) {
            return notConfiguredBars();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", trackService.groupPercents(userId));
        result.put("unit", "%");
        return result;
    }

    @PostMapping("/progress")
    public ResponseEntity<Map<String, Object>> saveProgress(@RequestBody(required = false) Map<String, String> body) {
        String userId = ownerUserId();
        if (userId.isBlank()) {
            return ResponseEntity.ok(notConfiguredMessage());
        }
        Map<String, String> payload = body == null ? Map.of() : body;
        String message = trackService.saveProgress(userId, value(payload, "subject"), value(payload, "group"),
                value(payload, "phase"), value(payload, "title"), number(payload.get("total")),
                number(payload.get("done")), value(payload, "unit"), value(payload, "dueDate"),
                value(payload, "note"));
        return ResponseEntity.ok(message(message, "进度条要有个单元名", "截止日期没看懂"));
    }

    /** 行内推进：{"id":12,"delta":1} 或 {"id":12,"done":120} */
    @PostMapping("/progress/bump")
    public ResponseEntity<Map<String, Object>> bumpProgress(@RequestBody(required = false) Map<String, Object> body) {
        String userId = ownerUserId();
        if (userId.isBlank()) {
            return ResponseEntity.ok(notConfiguredMessage());
        }
        Map<String, Object> payload = body == null ? Map.of() : body;
        String message = trackService.bumpProgress(userId, longValue(payload.get("id")),
                intValue(payload.get("delta")), intValue(payload.get("done")));
        return ResponseEntity.ok(message(message, "没找到这条进度"));
    }

    @PostMapping("/progress/delete")
    public ResponseEntity<Map<String, Object>> deleteProgress(@RequestBody(required = false) Map<String, Object> body) {
        String userId = ownerUserId();
        if (userId.isBlank()) {
            return ResponseEntity.ok(notConfiguredMessage());
        }
        Map<String, Object> payload = body == null ? Map.of() : body;
        return ResponseEntity.ok(message(trackService.deleteProgress(userId, longValue(payload.get("id"))), "没找到"));
    }

    // ==================== 错题本 ====================

    @GetMapping("/mistakes")
    public Map<String, Object> mistakes() {
        String userId = ownerUserId();
        return userId.isBlank() ? notConfigured() : trackService.mistakePanel(userId);
    }

    @PostMapping("/mistakes")
    public ResponseEntity<Map<String, Object>> addMistake(@RequestBody(required = false) Map<String, String> body) {
        String userId = ownerUserId();
        if (userId.isBlank()) {
            return ResponseEntity.ok(notConfiguredMessage());
        }
        Map<String, String> payload = body == null ? Map.of() : body;
        String message = trackService.addMistake(userId, value(payload, "subject"), value(payload, "title"),
                value(payload, "detail"), value(payload, "source"));
        return ResponseEntity.ok(message(message, "错题要有个摘要"));
    }

    /** 行内复习结果：{"id":3,"result":"RIGHT"|"WRONG"} */
    @PostMapping("/mistakes/review")
    public ResponseEntity<Map<String, Object>> reviewMistake(@RequestBody(required = false) Map<String, String> body) {
        String userId = ownerUserId();
        if (userId.isBlank()) {
            return ResponseEntity.ok(notConfiguredMessage());
        }
        Map<String, String> payload = body == null ? Map.of() : body;
        String message = trackService.reviewMistake(userId, longValue(payload.get("id")), value(payload, "result"));
        return ResponseEntity.ok(message(message, "没找到这条错题"));
    }

    // ==================== 里程碑 ====================

    @GetMapping("/milestones")
    public Map<String, Object> milestones() {
        String userId = ownerUserId();
        return userId.isBlank() ? notConfigured() : trackService.milestonePanel(userId);
    }

    @PostMapping("/milestones")
    public ResponseEntity<Map<String, Object>> saveMilestone(@RequestBody(required = false) Map<String, String> body) {
        String userId = ownerUserId();
        if (userId.isBlank()) {
            return ResponseEntity.ok(notConfiguredMessage());
        }
        Map<String, String> payload = body == null ? Map.of() : body;
        String message = trackService.saveMilestone(userId, value(payload, "name"), value(payload, "dueDate"),
                value(payload, "note"));
        return ResponseEntity.ok(message(message, "里程碑要有个名字", "截止日期没看懂"));
    }

    /** 行内勾选：{"id":2,"done":true|false} */
    @PostMapping("/milestones/done")
    public ResponseEntity<Map<String, Object>> doneMilestone(@RequestBody(required = false) Map<String, Object> body) {
        String userId = ownerUserId();
        if (userId.isBlank()) {
            return ResponseEntity.ok(notConfiguredMessage());
        }
        Map<String, Object> payload = body == null ? Map.of() : body;
        boolean done = !(payload.get("done") instanceof Boolean value) || value;
        return ResponseEntity.ok(message(trackService.completeMilestone(userId, longValue(payload.get("id")), done),
                "没找到这个里程碑"));
    }

    /** 行内改任务状态：{"id":12,"status":"DONE"|"SKIPPED"|"PENDING"} */
    @PostMapping("/tasks/status")
    public ResponseEntity<Map<String, Object>> taskStatus(@RequestBody(required = false) Map<String, String> body) {
        String userId = ownerUserId();
        if (userId.isBlank()) {
            return ResponseEntity.ok(notConfiguredMessage());
        }
        Map<String, String> payload = body == null ? Map.of() : body;
        String message = examService.updateTask(userId, longValue(payload.get("id")), value(payload, "keyword"),
                value(payload, "status"), value(payload, "note"));
        return ResponseEntity.ok(message(message, "没找到", "状态只认"));
    }

    private Map<String, Object> formResponse(Map<String, Object> values, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("values", values);
        if (message != null) {
            result.put("message", message);
        }
        return result;
    }

    private Map<String, Object> message(String text, String... failureMarkers) {
        boolean ok = true;
        for (String marker : failureMarkers) {
            if (text != null && text.contains(marker)) {
                ok = false;
                break;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", ok);
        result.put("message", text);
        return result;
    }

    private String value(Map<String, String> payload, String key) {
        String text = payload.get(key);
        return text == null ? "" : text.trim();
    }

    private Integer number(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(text.trim().replaceAll("[^0-9-]", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim().replaceAll("[^0-9-]", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer intValue(Object value) {
        Long parsed = longValue(value);
        return parsed == null ? null : parsed.intValue();
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
