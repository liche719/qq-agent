package com.liche.qqagent.controller;

import com.liche.qqagent.bilibili.BiliCredentialService;
import com.liche.qqagent.bilibili.BiliQrLoginService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 面板「B站」页签用的扫码登录接口（2026-10-20 加）。
 *
 * <p>为什么要有它：原来 B 站凭据只能手工复制 Cookie 到服务器 `.env`，而且那份**只有 SESSDATA**、
 * 缺 `bili_jct` 就发不了评论。扫码一次能同时拿到两者，所以做成面板上点一下就行。
 *
 * <p>这三个接口都在 `/api/admin/**` 下，跟着面板的鉴权走（口令头 / 会话），**不额外开公网口子**。
 * 返回体里**永远不会出现凭据原文**：只有"有没有 / 能不能写 / 昵称 / 时间"。
 */
@RestController
@RequestMapping("/api/admin/bilibili")
public class AdminBiliLoginController {

    private final BiliQrLoginService loginService;
    private final BiliCredentialService credentials;

    public AdminBiliLoginController(BiliQrLoginService loginService, BiliCredentialService credentials) {
        this.loginService = loginService;
        this.credentials = credentials;
    }

    /** 当前登录状态（面板加载时先问这个） */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return credentials.status();
    }

    /** 生成一张新二维码 */
    @PostMapping("/login/qrcode")
    public Map<String, Object> qrcode() {
        return loginService.generate();
    }

    /** 轮询扫码状态；成功时凭据当场存库 */
    @GetMapping("/login/poll")
    public Map<String, Object> poll(@RequestParam("key") String key) {
        return loginService.poll(key);
    }

    /** 退出登录（只清库里那份，服务器 .env 的兜底不动） */
    @DeleteMapping("/credential")
    public Map<String, Object> clear() {
        credentials.clear();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cleared", true);
        result.putAll(credentials.status());
        return result;
    }
}
