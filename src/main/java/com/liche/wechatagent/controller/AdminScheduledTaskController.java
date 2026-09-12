package com.liche.wechatagent.controller;

import com.liche.wechatagent.config.AlertProperties;
import com.liche.wechatagent.schedule.BuiltinScheduleService;
import com.liche.wechatagent.schedule.ScheduledTaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时任务（面板「定时任务」页）。
 *
 * <p>两块内容：**系统内置的定时任务**（{@link BuiltinScheduleService}，只读，让人看得见系统在自动做什么）
 * 与**用户创建的定时任务**（可新建/启停/立即执行/删除）。鉴权由 {@code AdminAccessFilter} 统一处理。
 */
@RestController
@RequestMapping("/api/admin/scheduled")
public class AdminScheduledTaskController {

    private final ScheduledTaskService taskService;
    private final BuiltinScheduleService builtinService;
    private final AlertProperties alertProperties;

    public AdminScheduledTaskController(ScheduledTaskService taskService,
                                        BuiltinScheduleService builtinService,
                                        AlertProperties alertProperties) {
        this.taskService = taskService;
        this.builtinService = builtinService;
        this.alertProperties = alertProperties;
    }

    /** 我创建的定时任务 + 系统内置定时任务 */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        String owner = ownerUserId();
        result.put("ownerUserId", owner);
        result.put("tasks", owner.isBlank() ? List.of() : taskService.describeAll(owner));
        result.put("builtin", builtinService.list());
        result.put("cronHint", "Quartz 6 段：秒 分 时 日 月 周；每天 8 点 = 0 0 8 * * ?；每周一 9 点 = 0 0 9 ? * MON");
        return result;
    }

    /** 面板新建：{"title":"…","instruction":"…","cron":"0 0 8 * * ?","userId":"可选"} */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) Map<String, String> body) {
        Map<String, String> payload = body == null ? Map.of() : body;
        String userId = value(payload, "userId");
        if (userId.isBlank()) {
            userId = ownerUserId();
        }
        String title = value(payload, "title");
        String instruction = value(payload, "instruction");
        String cron = value(payload, "cron");
        Map<String, Object> result = new LinkedHashMap<>();
        if (userId.isBlank()) {
            result.put("accepted", false);
            result.put("message", "没有可用的用户：请先配置 alert.qq-openid，或在请求里带上 userId");
            return ResponseEntity.ok(result);
        }
        String message = taskService.create(userId, title, instruction, cron);
        boolean ok = !message.contains("不合法") && !message.contains("失败") && !message.contains("还差");
        result.put("accepted", ok);
        result.put("message", message);
        result.put("tasks", taskService.describeAll(userId));
        return ResponseEntity.ok(result);
    }

    /** 暂停 / 恢复：{"enabled":true|false} */
    @PostMapping("/{id}/toggle")
    public Map<String, Object> toggle(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        boolean enabled = body == null || !(body.get("enabled") instanceof Boolean value) || value;
        String message = taskService.setEnabled(ownerUserId(), id, enabled);
        return response(message);
    }

    /** 立刻执行一次（后台跑，结果推送并写回 lastResult） */
    @PostMapping("/{id}/run")
    public Map<String, Object> run(@PathVariable Long id) {
        return response(taskService.runNow(ownerUserId(), id));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        return response(taskService.cancel(ownerUserId(), id));
    }

    private Map<String, Object> response(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", !message.contains("没找到") && !message.contains("失败"));
        result.put("message", message);
        result.put("tasks", taskService.describeAll(ownerUserId()));
        return result;
    }

    /**
     * 面板操作归属的用户：QQ 私聊里 userId 就是 openid，
     * 所以直接复用运维告警里配置的本人 openid；没配置时返回空（由调用方提示）。
     */
    private String ownerUserId() {
        String openid = alertProperties.getQqOpenid();
        return openid == null ? "" : openid.trim();
    }

    private static String value(Map<String, String> body, String key) {
        String value = body.get(key);
        return value == null ? "" : value.trim();
    }
}
