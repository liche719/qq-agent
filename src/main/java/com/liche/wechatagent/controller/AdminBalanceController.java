package com.liche.wechatagent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liche.wechatagent.metrics.LlmCallAuditRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
        out.put("note", "今天花掉的钱（对话/提取/视频/生图/语音合计）");

        // 分类明细：按场景把今天的花费摊开，钱花在哪一眼能看出来。
        // 生图那类是"没 token 按张折的"，所以 yuan 要按张数重算一遍，不能直接用表里的值（那是 0）。
        try {
            java.util.List<Map<String, Object>> rows = audit.byScenario(1);
            java.util.List<Map<String, Object>> breakdown = new java.util.ArrayList<>();
            for (Map<String, Object> row : rows) {
                String scenario = String.valueOf(row.get("scenario"));
                double yuan = row.get("yuan") instanceof Number n ? n.doubleValue() : 0d;
                long calls = row.get("calls") instanceof Number n ? n.longValue() : 0L;
                // 生图的"按张折钱"已经在 audit.byScenario 里算好了（2026-10-21 挪过去的），
                // 这里不再重算，否则两处单价各写一份，改一处就会不一致。
                if (yuan <= 0 && calls <= 0) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("scenario", scenario);
                item.put("calls", calls);
                item.put("yuan", String.format(java.util.Locale.ROOT, "%.2f", yuan));
                breakdown.add(item);
            }
            breakdown.sort((a, b) -> Double.compare(
                    Double.parseDouble(String.valueOf(b.get("yuan"))),
                    Double.parseDouble(String.valueOf(a.get("yuan")))));
            out.put("breakdown", breakdown);
        } catch (RuntimeException exception) {
            log.warn("统计分类花费失败（已忽略）：{}", exception.toString());
        }

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

    /**
     * 用量明细（2026-10-21）：**一个地方**看全每个场景花了多少 token、多少钱。
     *
     * <p>为什么单独一个接口：原来只有 {@code /balance} 的"今天 + 分类"，而分类明细只藏在
     * 顶栏数字的 tooltip 里，看不到 token 分布、也看不到历史。这个接口给面板的「花费」页用，
     * 一次返回区间内的场景汇总 + 按天汇总。
     *
     * @param days 1=今天，7=近一周，30=近一月（1~90）
     */
    @GetMapping("/usage")
    public Map<String, Object> usage(@RequestParam(defaultValue = "7") int days) {
        int bounded = Math.max(1, Math.min(90, days));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", bounded);
        try {
            out.put("todayYuan", audit.todayYuan().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
            out.put("rangeYuan", audit.totalYuanLastDays(bounded)
                    .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
            out.put("scenarios", audit.byScenario(bounded));
            out.put("daily", audit.byDay(bounded));
        } catch (RuntimeException exception) {
            log.warn("统计用量明细失败：{}", exception.toString());
            out.put("error", "统计失败，稍后再试");
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
