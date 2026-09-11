package com.liche.wechatagent.controller;

import com.liche.wechatagent.alert.AlertNotifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 运维告警：面板上的“发送测试告警”走这里（鉴权由 AdminAccessFilter 统一处理） */
@RestController
@RequestMapping("/api/admin")
public class AdminAlertController {

    private final AlertNotifier notifier;

    public AdminAlertController(AlertNotifier notifier) {
        this.notifier = notifier;
    }

    @PostMapping("/actions/alerts/test")
    public ResponseEntity<Map<String, Object>> test() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!notifier.ready()) {
            result.put("accepted", false);
            result.put("message", "告警未启用：需要配置 alert.enabled 与 alert.qq-openid");
            return ResponseEntity.ok(result);
        }
        boolean sent = notifier.sendTest();
        result.put("accepted", sent);
        result.put("message", sent ? "测试告警已发送" : "发送失败，可能是 QQ 主动消息额度限制");
        return ResponseEntity.ok(result);
    }
}
