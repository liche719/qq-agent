package com.liche.qqagent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆精排（2026-11-04）。
 *
 * <p><b>为什么要它</b>：实测 16 条真实问题，召回率 100%——但代价是**每轮往提示词里塞
 * 1900~3500 字记忆**。这不是"找得准"，是"塞得多"。噪音既占上下文，也会把模型带偏。
 *
 * <p><b>做法</b>：先照旧召回（K 不变，召回率不掉），再用交叉编码器精排，
 * 按**分数断层**截断，只留真正相关的那几条（目标：注入字数降到 600 以内）。
 *
 * <p><b>判据</b>：不靠绝对阈值（rerank 分数没校准，不同查询分布不同），靠
 * ① 相邻分数差 &gt; gap 就停；② 最多留 topN 条；③ 绝对分低于 floor 的一律不要（粗筛）。
 */
@Component
public class MemoryRerankClient {

    private static final Logger log = LoggerFactory.getLogger(MemoryRerankClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final double gap;
    private final double floor;
    private final int timeoutSeconds;
    private final HttpClient http;

    /** 候选少于这个数就不精排（2026-11-04）：本来就没几条，精排纯属浪费钱 */
    private final int minDocs;

    /** 单价（元/千篇次）。qwen3.7-text-rerank 按篇计费，先用估算值，拿到账单再校 */
    private final double pricePer1kDocs;

    /** 记账出口：精排以前不进审计，面板上只看到总数涨、看不到花在哪（2026-11-04 补） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<com.liche.qqagent.config.LlmCallSink> auditSink;

    public MemoryRerankClient(
            @Value("${memory.rerank.enabled:false}") boolean enabled,
            @Value("${memory.rerank.base-url:https://dashscope.aliyuncs.com}") String baseUrl,
            @Value("${memory.rerank.api-key:${DASHSCOPE_API_KEY:}}") String apiKey,
            @Value("${memory.rerank.model:qwen3.7-text-rerank}") String model,
            @Value("${memory.rerank.gap:0.15}") double gap,
            @Value("${memory.rerank.floor:0.30}") double floor,
            @Value("${memory.rerank.timeout-seconds:10}") int timeoutSeconds,
            @Value("${memory.rerank.min-docs:8}") int minDocs,
            @Value("${memory.rerank.price-yuan-per-1k-docs:0.26}") double pricePer1kDocs) {
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
        this.gap = gap;
        this.floor = floor;
        this.timeoutSeconds = Math.max(3, Math.min(30, timeoutSeconds));
        this.minDocs = Math.max(2, minDocs);
        this.pricePer1kDocs = Math.max(0, pricePer1kDocs);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public boolean enabled() {
        return enabled && !baseUrl.isEmpty() && !apiKey.isEmpty() && !model.isEmpty();
    }

    /**
     * 精排并按断层截断。
     *
     * @param query      用户这句话
     * @param documents  候选文本（顺序与返回值一致）
     * @param maxKeep    最多留几条
     * @return 保留下来的**原始下标**（按分数从高到低）；未启用或失败时返回 null，调用方保持原行为
     */
    public List<Integer> rank(String query, List<String> documents, int maxKeep) {
        if (!enabled() || query == null || query.isBlank() || documents == null) {
            return null;
        }
        if (documents.size() < minDocs) {
            log.debug("候选只有 {} 条，跳过精排（省钱）", documents.size());
            return null;
        }
        long started = System.currentTimeMillis();
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("query", query);
            input.put("documents", documents);
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("return_documents", false);
            parameters.put("top_n", Math.min(documents.size(), Math.max(maxKeep * 2, maxKeep)));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("input", input);
            body.put("parameters", parameters);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "")
                            + "/api/v1/services/rerank/text-rerank/text-rerank"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("记忆精排失败 status={} body={}", response.statusCode(), clip(response.body(), 160));
                return null;
            }
            JsonNode results = JSON.readTree(response.body()).path("output").path("results");
            if (!results.isArray() || results.isEmpty()) {
                return null;
            }
            List<Map.Entry<Integer, Double>> scored = new ArrayList<>();
            for (JsonNode node : results) {
                scored.add(Map.entry(node.path("index").asInt(-1), node.path("relevance_score").asDouble(0)));
            }
            scored.removeIf(entry -> entry.getKey() < 0);
            scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            List<Integer> kept = new ArrayList<>();
            double previous = Double.MAX_VALUE;
            for (Map.Entry<Integer, Double> entry : scored) {
                if (kept.size() >= maxKeep) {
                    break;
                }
                if (entry.getValue() < floor) {
                    break;
                }
                // 分数断层：掉得太多说明后面的已经不相关了。
                // 注意**第一条不做断层判断**——previous 初值是 MAX_VALUE，否则第一条就被自己拦掉（2026-11-04 踩过）
                if (!kept.isEmpty() && previous - entry.getValue() > gap) {
                    break;
                }
                kept.add(entry.getKey());
                previous = entry.getValue();
            }
            recordCost(documents.size(), System.currentTimeMillis() - started);
            log.info("记忆精排 候选={} 保留={} 首分={} 末分={}", documents.size(), kept.size(),
                    scored.isEmpty() ? "-" : String.format("%.3f", scored.get(0).getValue()),
                    kept.isEmpty() ? "-" : String.format("%.3f", scored.get(kept.size() - 1).getValue()));
            return kept;
        } catch (Exception exception) {
            log.warn("记忆精排异常（退回原顺序）：{}", exception.toString());
            return null;
        }
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** 记一笔精排开销：按篇计费，和 token 计价的调用分开记 */
    private void recordCost(int docs, long durationMs) {
        try {
            var sink = auditSink == null ? null : auditSink.getIfAvailable();
            if (sink == null) {
                return;
            }
            java.math.BigDecimal cost = java.math.BigDecimal
                    .valueOf(pricePer1kDocs * docs / 1000.0)
                    .setScale(6, java.math.RoundingMode.HALF_UP);
            sink.acceptCall(new com.liche.qqagent.config.LlmCallEvent(
                    "rerank", false, true, durationMs,
                    com.liche.qqagent.config.LlmUsage.EMPTY, "", cost));
        } catch (RuntimeException exception) {
            log.debug("精排记账失败（已忽略）：{}", exception.getMessage());
        }
    }

}
