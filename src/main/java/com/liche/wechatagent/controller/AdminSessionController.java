package com.liche.wechatagent.controller;

import com.liche.wechatagent.config.ManagementAccessProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运维面板登录校验。
 *
 * <p>口令由 {@link com.liche.wechatagent.config.AdminAccessFilter} 通过请求头
 * {@code X-Agent-Admin-Key} 校验（口令错误会被过滤器计入失败次数并触发封禁），
 * 本接口只负责校验账号名，因此前端拿到 200 即表示账号与口令都正确。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminSessionController {

    private final ManagementAccessProperties properties;

    public AdminSessionController(ManagementAccessProperties properties) {
        this.properties = properties;
    }

    @PostMapping("/session")
    public ResponseEntity<Map<String, Object>> session(@RequestBody(required = false) Map<String, String> body) {
        String username = body == null ? "" : String.valueOf(body.getOrDefault("username", "")).trim();
        if (username.isEmpty() || !username.equalsIgnoreCase(properties.getAdminUsername())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "账号或口令不正确"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("username", properties.getAdminUsername());
        return ResponseEntity.ok(result);
    }
}
