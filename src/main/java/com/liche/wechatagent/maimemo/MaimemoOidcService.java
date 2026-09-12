package com.liche.wechatagent.maimemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 墨墨开放平台 OIDC 授权（长期方案，取代一天一换的个人 Token）。
 *
 * <p>流程：在 {@code open.maimemo.com/app} 创建"后端应用"拿到 {@code client_id + client_secret}，
 * 走 OIDC Authorization Code 换到 {@code access_token}（1 小时）与 {@code refresh_token}（90 天，
 * 每次刷新都会续期）——所以**授权一次就能长期用**，程序自己刷新，不用再人工粘贴 Token。
 *
 * <p>回调地址必须与开放平台里登记的完全一致。为了在域名暂时不可用（例如 ICP 备案期间）时也能完成授权，
 * 面板同时提供"手动粘贴回调地址"：浏览器跳转失败时地址栏里仍然带着 {@code code}，复制回来即可。
 */
@Service
public class MaimemoOidcService {

    public static final String KEY_REFRESH_TOKEN = "oidc_refresh_token";
    public static final String KEY_ACCESS_TOKEN = "oidc_access_token";
    public static final String KEY_ACCESS_EXPIRES_AT = "oidc_access_expires_at";
    public static final String KEY_STATE = "oidc_state";
    public static final String KEY_STATE_AT = "oidc_state_at";
    public static final String KEY_SUBJECT = "oidc_subject";
    public static final String KEY_NAME = "oidc_name";
    public static final String KEY_AUTHORIZED_AT = "oidc_authorized_at";
    public static final String KEY_LAST_ERROR = "oidc_last_error";
    public static final String KEY_LAST_REFRESH_AT = "oidc_last_refresh_at";

    private static final Logger log = LoggerFactory.getLogger(MaimemoOidcService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long STATE_TTL_SECONDS = 1800;

    private final MaimemoSettingRepository settings;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String scopes;
    private final String authorizationEndpoint;
    private final String tokenEndpoint;
    private final int connectTimeoutSeconds;
    private final int readTimeoutSeconds;
    private final ZoneId zone;

    public MaimemoOidcService(MaimemoSettingRepository settings,
                              @Value("${maimemo.oidc.client-id:}") String clientId,
                              @Value("${maimemo.oidc.client-secret:}") String clientSecret,
                              @Value("${maimemo.oidc.redirect-uri:}") String redirectUri,
                              @Value("${maimemo.oidc.scopes:openid offline_access open.memo.study}") String scopes,
                              @Value("${maimemo.oidc.authorization-endpoint:https://accounts.maimemo.com/oidc/auth}") String authorizationEndpoint,
                              @Value("${maimemo.oidc.token-endpoint:https://accounts.maimemo.com/oidc/token}") String tokenEndpoint,
                              @Value("${maimemo.connect-timeout-seconds:5}") int connectTimeoutSeconds,
                              @Value("${maimemo.timeout-seconds:10}") int readTimeoutSeconds,
                              @Value("${app.time-zone:Asia/Shanghai}") String timeZone) {
        this.settings = settings;
        this.clientId = trim(clientId);
        this.clientSecret = trim(clientSecret);
        this.redirectUri = trim(redirectUri);
        this.scopes = trim(scopes);
        this.authorizationEndpoint = trim(authorizationEndpoint);
        this.tokenEndpoint = trim(tokenEndpoint);
        this.connectTimeoutSeconds = Math.max(1, Math.min(60, connectTimeoutSeconds));
        this.readTimeoutSeconds = Math.max(1, Math.min(120, readTimeoutSeconds));
        this.zone = parseZone(timeZone);
    }

    /** 面板上显示的授权状态 */
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("configured", configured());
        status.put("authorized", authorized());
        status.put("redirectUri", redirectUri);
        status.put("scopes", scopes);
        status.put("clientIdHint", clientId.length() > 8 ? clientId.substring(0, 8) + "…" : clientId);
        status.put("subject", value(KEY_SUBJECT));
        status.put("name", value(KEY_NAME));
        status.put("authorizedAt", value(KEY_AUTHORIZED_AT));
        status.put("accessExpiresAt", value(KEY_ACCESS_EXPIRES_AT));
        status.put("lastRefreshAt", value(KEY_LAST_REFRESH_AT));
        status.put("lastError", value(KEY_LAST_ERROR));
        status.put("accessTokenRemainingSeconds", accessTokenRemainingSeconds());
        return status;
    }

    public boolean configured() {
        return !clientId.isBlank() && !clientSecret.isBlank() && !redirectUri.isBlank();
    }

    public boolean authorized() {
        return !value(KEY_REFRESH_TOKEN).isBlank();
    }

    /** 生成授权链接（state 存库，30 分钟内有效） */
    public String authorizationUrl() {
        requireConfigured();
        String state = HexFormat.of().formatHex(random.generateSeed(16));
        save(KEY_STATE, state);
        save(KEY_STATE_AT, LocalDateTime.now(zone).format(STAMP));
        return authorizationEndpoint
                + "?response_type=code"
                + "&client_id=" + encode(clientId)
                + "&redirect_uri=" + encode(redirectUri)
                + "&scope=" + encode(scopes)
                + "&state=" + encode(state)
                + "&prompt=consent";
    }

    /**
     * 用回调里的 code 换 token。
     *
     * @param input 整条回调地址（浏览器地址栏里那串）或只有 code
     */
    public Map<String, Object> complete(String input) {
        requireConfigured();
        String raw = trim(input);
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("请粘贴回调地址或授权码");
        }
        String code = raw;
        String state = "";
        // 支持三种粘贴内容：整条回调地址、只有查询串、只有 code
        if (raw.startsWith("http") || raw.contains("code=")) {
            Map<String, String> query = parseQuery(raw);
            code = query.getOrDefault("code", "");
            state = query.getOrDefault("state", "");
            if (code.isBlank()) {
                String error = query.getOrDefault("error", "");
                if (!error.isBlank()) {
                    throw new IllegalArgumentException("授权被拒绝：" + error + " " + query.getOrDefault("error_description", ""));
                }
                throw new IllegalArgumentException("回调地址里没有 code，请确认复制的是授权后跳转的那一整条地址");
            }
        }
        String savedState = value(KEY_STATE);
        // state 必须校验：这个回调是**公网匿名可达**的，只校验"如果两边都有才比"等于没校验——
        // 任何拿到 client_id（授权链接里就有）的人都能用自己墨墨账号的 code 把服务端绑成他的账号。
        if (savedState.isBlank()) {
            throw new IllegalArgumentException("没有进行中的授权，请回到面板点「生成授权链接」再完成一次授权");
        }
        if (stateExpired()) {
            throw new IllegalArgumentException("这次授权链接已超过 30 分钟，请重新点击「生成授权链接」");
        }
        if (state.isBlank() || !savedState.equals(state)) {
            throw new IllegalArgumentException("state 不匹配（可能不是本次发起的授权），请粘贴浏览器跳转后地址栏里的**整条**地址");
        }

        JsonNode token = requestToken(Map.of(
                "grant_type", "authorization_code",
                "code", code,
                "redirect_uri", redirectUri));
        applyTokenResponse(token, true);
        save(KEY_AUTHORIZED_AT, LocalDateTime.now(zone).format(STAMP));
        save(KEY_LAST_ERROR, "");
        settings.deleteById(KEY_STATE);
        log.info("墨墨 OIDC 授权完成：sub={}", value(KEY_SUBJECT));
        Map<String, Object> result = new LinkedHashMap<>(status());
        result.put("message", "授权成功，之后程序会自动刷新，不用再管 Token 了");
        return result;
    }

    /** 断开授权（清掉本地保存的 refresh/access token） */
    public Map<String, Object> disconnect() {
        for (String key : new String[]{KEY_REFRESH_TOKEN, KEY_ACCESS_TOKEN, KEY_ACCESS_EXPIRES_AT,
                KEY_SUBJECT, KEY_NAME, KEY_AUTHORIZED_AT, KEY_LAST_ERROR, KEY_LAST_REFRESH_AT, KEY_STATE, KEY_STATE_AT}) {
            settings.deleteById(key);
        }
        Map<String, Object> result = new LinkedHashMap<>(status());
        result.put("message", "已断开墨墨 OIDC 授权");
        return result;
    }

    /**
     * 直接可用的 access token（已授权时返回，必要时自动刷新）。
     * 未授权返回 null，由调用方回落到面板 Token / 环境变量。
     */
    public String accessTokenOrNull() {
        if (!authorized()) {
            return null;
        }
        if (!needsRefresh()) {
            return value(KEY_ACCESS_TOKEN);
        }
        refresh();
        return value(KEY_ACCESS_TOKEN);
    }

    /** 401 之后强制刷新一次 */
    public void forceRefresh() {
        if (authorized()) {
            refresh();
        }
    }

    private boolean needsRefresh() {
        String access = value(KEY_ACCESS_TOKEN);
        if (access.isBlank()) {
            return true;
        }
        String expiresAt = value(KEY_ACCESS_EXPIRES_AT);
        if (expiresAt.isBlank()) {
            return true;
        }
        try {
            LocalDateTime expiry = LocalDateTime.parse(expiresAt, STAMP);
            // 提前 2 分钟刷新，避免边界上刚好过期
            return LocalDateTime.now(zone).plusMinutes(2).isAfter(expiry);
        } catch (RuntimeException exception) {
            return true;
        }
    }

    private void refresh() {
        String refreshToken = value(KEY_REFRESH_TOKEN);
        if (refreshToken.isBlank()) {
            throw new MaimemoClient.MaimemoAuthException("墨墨 OIDC 授权已失效，请在面板重新授权");
        }
        try {
            JsonNode token = requestToken(Map.of(
                    "grant_type", "refresh_token",
                    "refresh_token", refreshToken));
            applyTokenResponse(token, false);
            save(KEY_LAST_REFRESH_AT, LocalDateTime.now(zone).format(STAMP));
            save(KEY_LAST_ERROR, "");
        } catch (RuntimeException exception) {
            String message = "刷新墨墨 access token 失败：" + exception.getMessage() + "（可能需要重新授权）";
            save(KEY_LAST_ERROR, message);
            log.warn("{}", message);
            throw new MaimemoClient.MaimemoAuthException(message);
        }
    }

    private void applyTokenResponse(JsonNode token, boolean requireRefreshToken) {
        String access = token.path("access_token").asText("");
        if (access.isBlank()) {
            throw new RuntimeException("墨墨授权接口没有返回 access_token");
        }
        long expiresIn = token.path("expires_in").asLong(3600);
        save(KEY_ACCESS_TOKEN, access);
        save(KEY_ACCESS_EXPIRES_AT, LocalDateTime.now(zone).plusSeconds(Math.max(60, expiresIn - 60)).format(STAMP));
        String refresh = token.path("refresh_token").asText("");
        if (!refresh.isBlank()) {
            save(KEY_REFRESH_TOKEN, refresh);
        } else if (requireRefreshToken && value(KEY_REFRESH_TOKEN).isBlank()) {
            throw new RuntimeException("墨墨没有返回 refresh_token：请确认应用已批准 offline_access 权限");
        }
        String idToken = token.path("id_token").asText("");
        if (!idToken.isBlank()) {
            JsonNode claims = decodeJwtPayload(idToken);
            if (claims != null) {
                if (!claims.path("sub").asText("").isBlank()) {
                    save(KEY_SUBJECT, claims.path("sub").asText(""));
                }
                if (!claims.path("name").asText("").isBlank()) {
                    save(KEY_NAME, claims.path("name").asText(""));
                }
            }
        }
    }

    private JsonNode requestToken(Map<String, String> form) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        form.forEach(body::add);
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        String json;
        try {
            json = RestClient.builder().requestFactory(factory).build()
                    .post()
                    .uri(URI.create(tokenEndpoint))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (org.springframework.web.client.RestClientResponseException exception) {
            // 上游响应体只进日志：这个异常文案会渲染到**匿名可达**的回调页上，回显上游内容等于把内部细节交出去
            log.warn("墨墨授权接口返回 {}：{}", exception.getStatusCode().value(),
                    brief(exception.getResponseBodyAsString()));
            throw new RuntimeException("墨墨授权接口返回 " + exception.getStatusCode().value()
                    + "，请检查 client_id / client_secret / 回调地址是否与平台登记的一致");
        } catch (RuntimeException exception) {
            throw new RuntimeException("墨墨授权接口不可达：" + exception.getMessage());
        }
        try {
            return objectMapper.readTree(json == null ? "{}" : json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            log.warn("解析墨墨授权接口响应失败：{}", brief(json));
            throw new RuntimeException("墨墨授权接口返回了无法解析的内容，请稍后重试");
        }
    }

    private JsonNode decodeJwtPayload(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            return objectMapper.readTree(new String(decoded, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            log.debug("解析 id_token 失败：{}", exception.toString());
            return null;
        }
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new IllegalStateException("还没有配置墨墨 OIDC 的 client_id / client_secret / 回调地址");
        }
    }

    private String value(String key) {
        return Optional.ofNullable(settings.findById(key).map(MaimemoSetting::getValue).orElse("")).orElse("");
    }

    private void save(String key, String value) {
        MaimemoSetting setting = settings.findById(key).orElseGet(MaimemoSetting::new);
        setting.setKey(key);
        setting.setValue(value == null ? "" : value);
        setting.setUpdatedAt(LocalDateTime.now(zone));
        settings.save(setting);
    }

    private static Map<String, String> parseQuery(String url) {
        Map<String, String> result = new LinkedHashMap<>();
        int index = url.indexOf('?');
        String query = index < 0 ? url : url.substring(index + 1);
        if (query.isBlank()) {
            return result;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String brief(String value) {
        if (value == null) {
            return "";
        }
        String flat = value.replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }

    private static ZoneId parseZone(String value) {
        try {
            return ZoneId.of(value);
        } catch (RuntimeException ignored) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    /** 预留：state 过期判断（超时则要求重新发起） */
    private boolean stateExpired() {
        String at = value(KEY_STATE_AT);
        if (at.isBlank()) {
            return true;
        }
        try {
            return LocalDateTime.parse(at, STAMP).plusSeconds(STATE_TTL_SECONDS).isBefore(LocalDateTime.now(zone));
        } catch (RuntimeException exception) {
            return true;
        }
    }

    /** 当前 access token 的剩余秒数（面板展示"自动续期"是否生效） */
    private long accessTokenRemainingSeconds() {
        String expiresAt = value(KEY_ACCESS_EXPIRES_AT);
        if (expiresAt.isBlank()) {
            return 0;
        }
        try {
            LocalDateTime expiry = LocalDateTime.parse(expiresAt, STAMP);
            return Math.max(0, Duration.between(LocalDateTime.now(zone), expiry).getSeconds());
        } catch (RuntimeException exception) {
            return 0;
        }
    }
}
