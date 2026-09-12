package com.liche.wechatagent.controller;

import com.liche.wechatagent.maimemo.MaimemoPushService;
import com.liche.wechatagent.maimemo.MaimemoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 墨墨背单词接入（面板「背单词」页）。
 *
 * <p>Token 变化很频繁（个人 access token 有效期短），所以这里提供保存入口：
 * 用户从墨墨 App 复制新 Token 粘进来即可，不用登录服务器改 {@code .env}。
 * 鉴权由 {@code AdminAccessFilter} 统一处理。
 */
@RestController
@RequestMapping("/api/admin/maimemo")
public class AdminMaimemoController {

    private final MaimemoService maimemoService;
    private final MaimemoPushService pushService;

    public AdminMaimemoController(MaimemoService maimemoService, MaimemoPushService pushService) {
        this.maimemoService = maimemoService;
        this.pushService = pushService;
    }

    /** 进度、今日单词、学习记录、Token 与推送状态（带 30 秒缓存） */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return maimemoService.snapshot();
    }

    /** 跳过缓存立刻取一次 */
    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        return maimemoService.refresh();
    }

    /** 保存或清除 Token（token 传空字符串即清除面板里的值，回落到环境变量） */
    @PostMapping("/token")
    public Map<String, Object> saveToken(@RequestBody(required = false) Map<String, String> body) {
        String token = body == null ? "" : String.valueOf(body.getOrDefault("token", ""));
        return maimemoService.saveToken(token);
    }

    /** 保存每日推送设置：{"enabled":true,"time":"21:30"} */
    @PostMapping("/push/settings")
    public ResponseEntity<Map<String, Object>> savePushSettings(@RequestBody(required = false) Map<String, Object> body) {
        Boolean enabled = null;
        if (body != null && body.get("enabled") instanceof Boolean value) {
            enabled = value;
        }
        String time = body == null || body.get("time") == null ? null : String.valueOf(body.get("time"));
        try {
            maimemoService.savePush(enabled, time);
        } catch (IllegalArgumentException exception) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("accepted", false);
            error.put("message", exception.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", true);
        result.put("message", "推送设置已保存");
        result.put("push", maimemoService.pushState());
        return ResponseEntity.ok(result);
    }

    /** 立即推送一次今日进度（用于验证推送链路是否通） */
    @PostMapping("/push/now")
    public Map<String, Object> pushNow() {
        Map<String, Object> result = new LinkedHashMap<>(pushService.pushNow());
        result.put("push", maimemoService.pushState());
        return result;
    }
}
