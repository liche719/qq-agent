package com.liche.qqagent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liche.qqagent.metrics.LlmCallAuditRecorder;
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
 * <p>缓存：聚合结果放 Redis（{@code panel:balance:v1} / {@code panel:usage:v1:N}，TTL 可配，默认 30 秒）。
 * **真源仍是 PostgreSQL**——缓存只是省掉重复的 group by 和余额接口调用，过期后照常从库里重算。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminBalanceController {

    private static final Logger log = LoggerFactory.getLogger(AdminBalanceController.class);
    private static final String BALANCE_CACHE_KEY = "panel:balance:v1";
    private static final String USAGE_CACHE_KEY_PREFIX = "panel:usage:v1:";

    private final LlmCallAuditRecorder audit;
    private final com.liche.qqagent.metrics.PanelCache panelCache;
    private final String searchApiKey;
    private final String searchBalanceUrl;
    /** 场景 → 模型名的映射（审计表里只有场景，模型是按配置推出来的） */
    private final java.util.Map<String, String> modelByScenario;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    public AdminBalanceController(LlmCallAuditRecorder audit,
                                  com.liche.qqagent.metrics.PanelCache panelCache,
                                  @Value("${llm.model:}") String dialogModel,
                                  @Value("${llm.video-model:}") String videoModel,
                                  @Value("${llm.image-model:}") String imageModel,
                                  @Value("${voice.tts.model:}") String voiceModel,
                                  @Value("${deepseek.search.api-key:}") String searchApiKey,
                                  @Value("${deepseek.search.base-url:https://api.deepseek.com}") String searchBaseUrl) {
        this.modelByScenario = buildModelMap(dialogModel, videoModel, imageModel, voiceModel);
        this.audit = audit;
        this.panelCache = panelCache;
        this.searchApiKey = searchApiKey == null ? "" : searchApiKey.trim();
        this.searchBalanceUrl = this.searchApiKey.isBlank()
                ? ""
                : searchBaseUrl.replaceAll("/v1/?$", "") + "/user/balance";
    }

    @GetMapping("/balance")
    public Map<String, Object> balance() {
        Map<String, Object> cached = panelCache.get(BALANCE_CACHE_KEY);
        if (cached != null) {
            // checkedAt 是"这次读取的时间"，不该跟着缓存一起变旧
            cached.put("checkedAt", Instant.now().toString());
            cached.put("cached", true);
            return cached;
        }
        Map<String, Object> result = query();
        result.put("cached", false);
        panelCache.put(BALANCE_CACHE_KEY, result);
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
        String cacheKey = USAGE_CACHE_KEY_PREFIX + bounded;
        Map<String, Object> cached = panelCache.get(cacheKey);
        if (cached != null) {
            cached.put("checkedAt", Instant.now().toString());
            cached.put("cached", true);
            return cached;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", bounded);
        try {
            out.put("todayYuan", audit.todayYuan().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
            out.put("rangeYuan", audit.totalYuanLastDays(bounded)
                    .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
            out.put("scenarios", withModels(audit.byScenario(bounded)));
            out.put("daily", audit.byDay(bounded));
        } catch (RuntimeException exception) {
            log.warn("统计用量明细失败：{}", exception.toString());
            out.put("error", "统计失败，稍后再试");
            return out;
        }
        out.put("cached", false);
        out.put("checkedAt", Instant.now().toString());
        panelCache.put(cacheKey, out);
        return out;
    }

    /**
     * 给每个场景补上"用的是哪个模型"（2026-10-22 用户反馈：花费页看不到刷视频用的模型）。
     *
     * <p>审计表里只存场景、不存模型——模型是按配置推的，所以这里按场景映射，
     * 换模型时只要改配置，不用改库、也不用重算历史。
     */
    private java.util.List<Map<String, Object>> withModels(java.util.List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return rows == null ? java.util.List.of() : rows;
        }
        for (Map<String, Object> row : rows) {
            String scenario = String.valueOf(row.get("scenario"));
            row.put("model", modelByScenario.getOrDefault(scenario, ""));
        }
        return rows;
    }

    private static java.util.Map<String, String> buildModelMap(String dialogModel, String videoModel,
                                                               String imageModel, String voiceModel) {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        String dialog = dialogModel == null ? "" : dialogModel.trim();
        for (String scenario : java.util.List.of("dialog", "dialog_first", "extract", "reflect",
                "self_comment", "reminder_parse", "schedule_parse", "unknown")) {
            map.put(scenario, dialog);
        }
        map.put("video", videoModel == null ? "" : videoModel.trim());
        map.put("image", imageModel == null ? "" : imageModel.trim());
        map.put("voice", voiceModel == null ? "" : voiceModel.trim());
        return map;
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
