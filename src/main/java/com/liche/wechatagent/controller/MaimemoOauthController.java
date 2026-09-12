package com.liche.wechatagent.controller;

import com.liche.wechatagent.maimemo.MaimemoOidcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 墨墨 OIDC 授权的回调入口（浏览器直接访问，因此**不能**要求管理员口令）。
 *
 * <p>开放平台里登记的回调地址要指向这里，例如 {@code https://liche.cloud/api/maimemo/oauth/callback}。
 * 只做一件事：把 code 换成长期凭据，然后渲染一页"授权成功"。这里不回显任何数据。
 */
@RestController
@RequestMapping("/api/maimemo/oauth")
public class MaimemoOauthController {

    private static final Logger log = LoggerFactory.getLogger(MaimemoOauthController.class);

    private final MaimemoOidcService oidcService;

    public MaimemoOauthController(MaimemoOidcService oidcService) {
        this.oidcService = oidcService;
    }

    @GetMapping(value = "/callback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> callback(@RequestParam(required = false) String code,
                                           @RequestParam(required = false) String state,
                                           @RequestParam(required = false) String error,
                                           @RequestParam(name = "error_description", required = false) String errorDescription) {
        if (error != null && !error.isBlank()) {
            return page(false, "授权被拒绝：" + error + (errorDescription == null ? "" : " " + errorDescription));
        }
        if (code == null || code.isBlank()) {
            return page(false, "没有收到授权码，请重新在面板里发起授权。");
        }
        try {
            oidcService.complete("code=" + code + (state == null ? "" : "&state=" + state));
            return page(true, "授权成功，墨墨背单词已经可以长期自动同步了。这个页面可以关闭。");
        } catch (RuntimeException exception) {
            log.warn("墨墨 OIDC 回调处理失败：{}", exception.getMessage());
            return page(false, "授权失败：" + exception.getMessage());
        }
    }

    private ResponseEntity<String> page(boolean ok, String message) {
        String title = ok ? "授权成功" : "授权失败";
        String color = ok ? "#1f8a4c" : "#b3261e";
        String html = """
                <!DOCTYPE html>
                <html lang="zh-CN"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%s</title></head>
                <body style="margin:0;display:flex;align-items:center;justify-content:center;min-height:100vh;background:#f6f8fc;font-family:system-ui,-apple-system,'Segoe UI',sans-serif;color:#1b1f27">
                <div style="max-width:460px;padding:32px 28px;border-radius:18px;background:#fff;box-shadow:0 12px 40px rgba(30,50,90,.10);text-align:center">
                <h1 style="margin:0 0 12px;font-size:20px;color:%s">%s</h1>
                <p style="margin:0;line-height:1.7;font-size:14.5px;color:#4a5262">%s</p>
                </div></body></html>
                """.formatted(title, color, title, message);
        return ResponseEntity.ok(html);
    }
}
