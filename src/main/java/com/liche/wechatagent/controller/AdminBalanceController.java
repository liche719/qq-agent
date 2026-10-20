package com.liche.wechatagent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 账户余额（2026-10-20 加，用户要求："加一个余额剩余量在前端显眼的地方"）。
 *
 * <p>为什么要单独一个接口：这个数来自 **DeepSeek 的 {@code /user/balance}**，
 * 是一次外部 HTTP 调用，不适合塞进 {@code /overview}（那个是本地查询，要快）。
 *
 * <p>缓存 5 分钟：面板刷新一次就调一次外部接口既慢又没必要，余额不会秒变。
 * 余额查不到时**不抛错**——面板上一个"查不到"比整页崩掉有用。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminBalanceController {

    private static final Logger log = LoggerFactory.getLogger(AdminBalanceController.class);
    private static final long CACHE_MILLIS = 5 * 60 * 1000L;

    private final String apiKey;
    private final String balanceUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private volatile Map<String, Object> cached;
    private volatile long cachedAt;

    public AdminBalanceController(@Value("${llm.api-key:}") String apiKey,
                                  @Value("${llm.base-url:https://api.deepseek.com/v1}") String baseUrl) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        String root = this.apiKey.isBlank() ? "" : baseUrl.replaceAll("/v1/?$", "");
        this.balanceUrl = root + "/user/balance";
    }

    @GetMapping("/balance")
    public Map<String, Object> balance() {
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAt < CACHE_MILLIS) {
            return cached;
        }
        Map<String, Object> result = query();
        cached = result;
        cachedAt = now;
        return result;
    }

    private Map<String, Object> query() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (apiKey.isBlank()) {
            out.put("available", false);
            out.put("error", "没配 LLM_API_KEY");
            return out;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(balanceUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                out.put("available", false);
                out.put("error", "余额接口 HTTP " + response.statusCode());
                return out;
            }
            JsonNode json = objectMapper.readTree(response.body());
            out.put("available", json.path("is_available").asBoolean(false));
            JsonNode infos = json.path("balance_infos");
            if (infos.isArray() && !infos.isEmpty()) {
                JsonNode first = infos.get(0);
                out.put("currency", first.path("currency").asText("CNY"));
                out.put("balance", first.path("total_balance").asText("0"));
                out.put("granted", first.path("granted_balance").asText("0"));
                out.put("toppedUp", first.path("topped_up_balance").asText("0"));
            }
            out.put("checkedAt", Instant.now().toString());
            return out;
        } catch (Exception exception) {
            // 查不到不等于没余额；如实标成"查不到"，面板会显示成灰色
            log.warn("查 LLM 余额失败：{}", exception.toString());
            out.put("available", false);
            out.put("error", exception.getClass().getSimpleName());
            return out;
        }
    }
}
