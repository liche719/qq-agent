package com.liche.wechatagent.controller;

import com.liche.wechatagent.channel.clawbot.ClawBotChannel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信 iLink 扫码登录管理（clawbot 模式下启用）：
 * POST /api/clawbot/register   生成登录二维码（无需名字，扫码即用），返回 {loginId, qrcodeUrl}
 * GET  /api/clawbot/bots       列出已登录机器人（key = 微信 openid）
 * POST /api/clawbot/logout?openid=xxx  退出登录并删除该 openid 凭证文件
 */
@RestController
@RequestMapping("/api/clawbot")
@ConditionalOnProperty(name = "wechat.channel.mode", havingValue = "clawbot")
public class ClawbotController {

    private final ClawBotChannel channel;

    public ClawbotController(ClawBotChannel channel) {
        this.channel = channel;
    }

    @PostMapping("/register")
    public Map<String, Object> register() {
        Map<String, String> r = channel.register();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loginId", r.get("loginId"));
        m.put("qrcodeUrl", r.get("qrcodeUrl"));
        m.put("message", "用手机微信扫描二维码登录（二维码内容为登录链接，可用任意二维码工具渲染）");
        return m;
    }

    @GetMapping("/bots")
    public List<Map<String, Object>> bots() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ClawBotChannel.BotSession s : channel.sessions()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("openid", s.userId);
            m.put("loggedIn", s.loggedIn());
            m.put("botId", s.botId);
            result.add(m);
        }
        return result;
    }

    @PostMapping("/logout")
    public Map<String, String> logout(@RequestParam String openid) {
        channel.logout(openid);
        return Map.of("status", "logged out: " + openid);
    }
}
