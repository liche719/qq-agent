package com.liche.wechatagent.bilibili;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * B 站扫码登录（2026-10-20 加）：生成二维码 → 轮询状态 → 成功后把 Cookie 存进库里。
 *
 * <p>用户原话是"不想每次都自己复制东西来登录"，所以做成面板上点一下就能扫。协议只有两步：
 * <ol>
 *   <li>{@code /x/passport-login/web/qrcode/generate} 拿 {@code qrcode_key} 和二维码内容；</li>
 *   <li>{@code /x/passport-login/web/qrcode/poll} 轮询：{@code 86101} 待扫、{@code 86090} 已扫待确认、
 *       {@code 86100} 成功、{@code 86038} 过期。
 *       成功时**响应头里的 {@code Set-Cookie} 才带着 SESSDATA 和 bili_jct**——这就是原来缺的那半。</li>
 * </ol>
 *
 * <p><b>为什么把二维码内容在服务端转成 PNG</b>：面板只要显示一张图就行，不用为二维码再引一个前端库；
 * 而且二维码内容（一个 b23.tv 短链）不算秘密，图片也不会泄露登录态。
 */
@Service
public class BiliQrLoginService {

    private static final Logger log = LoggerFactory.getLogger(BiliQrLoginService.class);
    private static final String GENERATE = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate";
    private static final String POLL = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    /** 一次扫码会话（key → 生成时间），只用来限制数量与判断过期 */
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final BiliCredentialService credentials;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(8))
            .readTimeout(Duration.ofSeconds(15))
            .build();

    public BiliQrLoginService(BiliCredentialService credentials) {
        this.credentials = credentials;
    }

    /** 要一张新二维码；返回 key、二维码内容、以及可以直接塞进 {@code <img src>} 的 PNG data URL */
    public Map<String, Object> generate() {
        cleanup();
        try {
            JsonNode root = read(GENERATE);
            if (root.path("code").asInt(-1) != 0) {
                throw new IllegalStateException("B站没给二维码：" + root.path("message").asText(""));
            }
            String key = root.path("data").path("qrcode_key").asText("");
            String url = root.path("data").path("url").asText("");
            if (key.isBlank() || url.isBlank()) {
                throw new IllegalStateException("B站返回的二维码是空的");
            }
            sessions.put(key, System.currentTimeMillis());
            log.info("B站扫码：已生成二维码 key={}…", key.substring(0, Math.min(8, key.length())));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("key", key);
            result.put("url", url);
            result.put("image", "data:image/png;base64," + pngBase64(url));
            result.put("expiresInSeconds", 180);
            return result;
        } catch (Exception e) {
            log.warn("生成 B站 登录二维码失败：{}", e.toString());
            throw new IllegalStateException("生成二维码失败：" + e.getMessage(), e);
        }
    }

    /**
     * 查一次扫码状态。成功时**当场把凭据存库**（只存一次，重复轮询不会重复写）。
     *
     * @return state = pending / scanned / confirmed / expired / unknown
     */
    public Map<String, Object> poll(String key) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (key == null || key.isBlank()) {
            result.put("state", "unknown");
            result.put("text", "没有二维码 key，先点一下生成");
            return result;
        }
        try {
            Response response = http.newCall(new Request.Builder()
                    .url(POLL + "?qrcode_key=" + key + "&source=main-fe-header")
                    .header("User-Agent", UA)
                    .header("Referer", "https://www.bilibili.com/")
                    .header("Origin", "https://www.bilibili.com")
                    .get().build()).execute();
            try (response) {
                if (!response.isSuccessful() || response.body() == null) {
                    result.put("state", "unknown");
                    result.put("text", "查询状态失败：HTTP " + response.code());
                    return result;
                }
                JsonNode root = objectMapper.readTree(response.body().string());
                int code = root.path("data").path("code").asInt(-1);
                result.put("state", stateOf(code));
                result.put("text", textOf(code));
                // 只记状态与码，不记 cookie（凭据绝不进日志）。排"扫了两次都没成功"这类问题时，
                // 这几行能直接看出是"没人扫"（一直 86101）、"扫了没确认"（停在 86090）还是"过期了"（86038）。
                log.info("B站扫码状态 code={} state={}", code, result.get("state"));
                // 注意这里是"状态是 confirmed"而不是 `code == 86100`：B站成功时回的是 0。
                // 2026-10-20 第一版只改了状态判断、没改这个分支，结果"面板显示登录成功、凭据没存"。
                if ("confirmed".equals(result.get("state"))) {
                    String cookie = mergeSetCookies(response.headers("Set-Cookie"));
                    // 只记字段名，不记值——万一以后哪次"成功但没拿到 bili_jct"，这条能一眼看出来
                    log.info("B站扫码：Set-Cookie 字段={}", BiliClientCookieSupport.names(cookie));
                    String mid = BiliClientCookieSupport.field(cookie, "DedeUserID");
                    String uname = "";
                    BilibiliClient.NavInfo nav = null;
                    try {
                        credentials.save(cookie, mid, uname);
                    } catch (RuntimeException saveFailure) {
                        result.put("state", "error");
                        result.put("text", saveFailure.getMessage());
                        return result;
                    }
                    // 用新凭据问一次昵称，显示在面板上（失败也不影响已保存的凭据）
                    try {
                        nav = new BilibiliClient(objectMapper, "", 15).navWithCookie(credentials.cookieHeader());
                        if (nav != null && nav.loggedIn()) {
                            credentials.save(cookie, String.valueOf(nav.mid()), nav.uname());
                            result.put("uname", nav.uname());
                            result.put("mid", nav.mid());
                        }
                    } catch (Exception ignored) {
                        // 昵称问不到不算失败
                    }
                    sessions.remove(key);
                    result.put("writable", credentials.current().writable());
                    log.info("B站扫码登录成功：uname={} 可写={}", result.get("uname"),
                            result.get("writable"));
                }
                if (code == 86038) {
                    sessions.remove(key);
                }
                return result;
            }
        } catch (Exception e) {
            result.put("state", "unknown");
            result.put("text", "查询状态出错：" + e.getMessage());
            return result;
        }
    }

    public Map<String, Object> status() {
        return credentials.status();
    }

    private JsonNode read(String url) throws Exception {
        try (Response response = http.newCall(new Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Referer", "https://www.bilibili.com/")
                .get().build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("HTTP " + response.code());
            }
            return objectMapper.readTree(response.body().string());
        }
    }

    private String pngBase64(String content) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 1);
        BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 320, 320, hints);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        }
    }

    /** Set-Cookie 多行合并成一条请求头，同名以后者为准 */
    public static String mergeSetCookies(List<String> setCookies) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (setCookies != null) {
            for (String raw : setCookies) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String pair = raw.split(";", 2)[0].trim();
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String name = pair.substring(0, eq).trim();
                String value = pair.substring(eq + 1).trim();
                if (value.isEmpty()) {
                    continue;
                }
                merged.put(name, value);
            }
        }
        StringBuilder cookie = new StringBuilder();
        merged.forEach((name, value) -> {
            if (cookie.length() > 0) {
                cookie.append("; ");
            }
            cookie.append(name).append('=').append(value);
        });
        return cookie.toString();
    }

    private String stateOf(int code) {
        return switch (code) {
            // 2026-10-20 实测：**登录成功时 data.code 是 0**（不是 86100）。
            // 按参考实现只认 86100 的话，成功那一帧会被当成 unknown 跳过，紧接着码被消费变成 86038，
            // 表现就是"手机显示登录成功、面板却说过期了、凭据没存下来"。两个都认。
            case 0, 86100 -> "confirmed";
            case 86101 -> "pending";
            case 86090 -> "scanned";
            case 86038 -> "expired";
            default -> "unknown";
        };
    }

    private String textOf(int code) {
        return switch (code) {
            case 0, 86100 -> "登录成功，凭据已保存";
            case 86101 -> "等待扫码（用 B 站手机客户端扫）";
            case 86090 -> "已扫码，请在手机上点确认";
            case 86038 -> "二维码过期了，重新点一次「生成二维码」";
            default -> "状态未知（B站返回 " + code + "）";
        };
    }

    /** 只留最近 10 分钟的会话，避免这张内存表无限长 */
    private void cleanup() {
        long cutoff = System.currentTimeMillis() - 10 * 60_000L;
        sessions.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }
}

