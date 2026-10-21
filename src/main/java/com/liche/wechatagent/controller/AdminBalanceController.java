package com.liche.wechatagent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liche.wechatagent.metrics.LlmCallAuditRecorder;
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
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 顶栏那个「今天花了多少」。
 *
 * <p>2026-10-21 改：原来是查 **DeepSeek 官方**的 {@code /user/balance}——那个接口只在
 * DeepSeek 的域名下存在。对话迁到基元律动之后它恒返回 404（基元律动没有余额 API，
 * {@code /api/me} 要的是网页 Cookie 而不是 Bearer），面板上就变成一个灰掉的错误。
 *
 * <p>现在改成显示**今天已经花掉的钱**（来自 {@code llm_call_audit} 的落库统计）：
 * 这个数比"余额"更有用——余额多了少了一眼看不出趋势，花费是每天都会动的。
 * 它涵盖对话、记忆提取、视频理解、生图（生图按张折算，见 {@code LlmCallAuditRecorder}）。
 *
 * <p>如果配了 {@code DEEPSEEK_SEARCH_API_KEY}，还会顺带带上 DeepSeek 官方余额
 * （搜索还在用那个账号，余额见底了搜索会先坏）。
 *
 * <p>缓存 60 秒：这个数由本地落库算出，本来就不贵，但面板每 10 秒刷一次也没必要重算。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminBalanceController {

    private static final Logger log = LoggerFactory.getLogger(AdminBalanceController.class);
    private static final long CACHE_MILLIS = 60 * 1000L;

    private final LlmCallAuditRecorder audit;
    private final String searchApiKey;
    private final String searchBalanceUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private volatile Map<String, Object> cached;
    private volatile long cachedAt;

    public AdminBalanceController(LlmCallAuditRecorder audit,
                                  @Value("${deepseek.search.api-key:}") String searchApiKey,
                                  @Value("${deepseek.search.base-url:https://api.deepseek.com}") String searchBaseUrl) {
        this.audit = audit;
        this.searchApiKey = searchApiKey == null ? "" : searchApiKey.trim();
        this.searchBalanceUrl = this.searchApiKey.isBlank()
                ? ""
                : searchBaseUrl.replaceAll("/v1/?$", "") + "/user/balance";
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
        out.put("currency", "CNY");

        // 今日花费：对话 + 提取 + 视频 + 生图
        BigDecimal today = BigDecimal.ZERO;
        try {
            BigDecimal value = audit.todayYuan();
            if (value != null) {
                today = value;
            }
        } catch (RuntimeException exception) {
            log.warn("统计今日花费失败：{}", exception.toString());
        }
        out.put("available", true);
        out.put("todayYuan", today.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
        out.put("note", "今天花掉的钱（对话/提取/视频/生图合计）");

        // 顺带看一眼 DS 官方余额（搜索还在用那个账号，余额见底会先影响搜索）
        if (!searchApiKey.isBlank()) {
            Map<String, Object> ds = queryDeepSeekBalance();
            if (ds != null) {
                out.put("searchBalance", ds);
            }
        }
        out.put("checkedAt", Instant.now().toString());
        return out;
    }

    /** 查 DeepSeek 官方余额；查不到返回 null（不污染主结果） */
    private Map<String, Object> queryDeepSeekBalance() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(searchBalanceUrl))
                    .header("Authorization", "Bearer " + searchApiKey)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            JsonNode json = objectMapper.readTree(response.body());
            JsonNode infos = json.path("balance_infos");
            if (!infos.isArray() || infos.isEmpty()) {
                return null;
            }
            JsonNode first = infos.get(0);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("currency", first.path("currency").asText("CNY"));
            out.put("total", first.path("total_balance").asText("0"));
            out.put("available", json.path("is_available").asBoolean(false));
            return out;
        } catch (Exception exception) {
            // 查不到就当没有；顶栏那个数才是主角
            return null;
        }
    }
}
