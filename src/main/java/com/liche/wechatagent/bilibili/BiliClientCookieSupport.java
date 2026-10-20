package com.liche.wechatagent.bilibili;

import java.util.Locale;

/**
 * Cookie 串的拼/拆小工具（2026-10-20 加）。
 *
 * <p>抽出来是因为**两处都要用**：{@link BilibiliClient} 造请求头、{@link BiliCredentialService} 从扫码响应里
 * 挑字段。以前这段逻辑写在 BilibiliClient 里（{@code buildCookieHeader}），搬出来免得两份各改一半。
 */
final class BiliClientCookieSupport {

    private BiliClientCookieSupport() {
    }

    /**
     * 把配置里的登录信息整理成 Cookie 头：整条 Cookie 串、或只有 SESSDATA 的值，都要认。
     * 缺 {@code buvid3} 时补一个随机值（不带它有些接口会返回空结果）。
     */
    static String normalize(String input) {
        String raw = input == null ? "" : input.trim();
        if (raw.isBlank()) {
            return "";
        }
        String header;
        if (raw.contains("=")) {
            StringBuilder joined = new StringBuilder();
            for (String part : raw.split("[;\\r\\n]+")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (joined.length() > 0) {
                    joined.append("; ");
                }
                joined.append(trimmed);
            }
            header = joined.toString();
        } else {
            header = "SESSDATA=" + raw;
        }
        if (!header.toLowerCase(Locale.ROOT).contains("buvid3=")) {
            header += "; buvid3=" + randomBuvid();
        }
        return header;
    }

    /** 从整条 Cookie 里取某个字段的值；没有就返回空串 */
    static String field(String cookie, String name) {
        if (cookie == null || cookie.isBlank()) {
            return "";
        }
        for (String part : cookie.split("[;\\r\\n]+")) {
            String trimmed = part.trim();
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (trimmed.substring(0, eq).trim().equalsIgnoreCase(name)) {
                return trimmed.substring(eq + 1).trim();
            }
        }
        return "";
    }

    private static String randomBuvid() {
        StringBuilder sb = new StringBuilder();
        String hex = java.util.UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        sb.append(hex, 0, 8).append('-');
        sb.append(hex, 8, 12).append('-');
        sb.append(hex, 12, 16).append('-');
        sb.append(hex, 16, 20);
        return sb.toString();
    }
}
