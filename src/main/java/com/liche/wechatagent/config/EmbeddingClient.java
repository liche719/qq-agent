package com.liche.wechatagent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 向量模型客户端（2026-09-18，记忆事实层用）。走 OpenAI 兼容的 {@code POST /embeddings}，
 * 默认指向阿里云百炼（`text-embedding-v4`，1024 维，0.0005 元/千 token，量大也用不了几分钱）。
 *
 * <p>自研而不是用 LangChain4j 的现成 embedding 模型：这里只需要"一次 HTTP + 解析 data[].embedding"，
 * 而且要和 {@link OpenAiCompatChatModel} 一样走 {@code SimpleClientHttpRequestFactory}（超时可控）。
 *
 * <p><b>没配 api-key 时 {@link #isEnabled()} 为 false，调用方降级成"只新增不合并"</b>——
 * 也就是不召回、不去重、不判矛盾，事实照样入库，功能不会因为缺密钥而不可用。
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    /** 单次批量上限：官方文档写 text-embedding-v4/qwen3.7 每请求最多 10 行 */
    private static final int BATCH_LIMIT = 10;
    /** 单条文本截断：事实很长时语义已经被前缀主导，多送只是浪费 token */
    private static final int MAX_INPUT_CHARS = 1000;

    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String model;
    private final int dimensions;
    private final boolean enabled;

    public EmbeddingClient(ObjectMapper objectMapper,
                           @Value("${embedding.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
                           @Value("${embedding.api-key:}") String apiKey,
                           @Value("${embedding.model:qwen3.7-text-embedding-flash}") String model,
                           @Value("${embedding.dimensions:1024}") int dimensions,
                           @Value("${embedding.timeout-seconds:20}") int timeoutSeconds,
                           @Value("${embedding.connect-timeout-seconds:5}") int connectTimeoutSeconds) {
        this.objectMapper = objectMapper;
        this.model = model;
        this.dimensions = dimensions;
        this.enabled = apiKey != null && !apiKey.isBlank() && baseUrl != null && !baseUrl.isBlank();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(Math.max(1, Math.min(60, connectTimeoutSeconds))));
        factory.setReadTimeout(Duration.ofSeconds(Math.max(1, Math.min(120, timeoutSeconds))));
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl).requestFactory(factory);
        if (enabled) {
            builder = builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        this.restClient = builder.build();
        if (!enabled) {
            log.warn("未配置 embedding.api-key（环境变量 EMBEDDING_API_KEY），记忆事实层降级为「只新增不合并」");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String model() {
        return model;
    }

    public int dimensions() {
        return dimensions;
    }

    /**
     * 批量取向量。**顺序与入参一一对应**；任何失败都返回 {@code null}（调用方按"没有向量"处理），
     * 不抛异常——事实入库这条链路不该被向量服务的抖动打断。
     */
    public List<float[]> embedAll(List<String> texts) {
        if (!enabled || texts == null || texts.isEmpty()) {
            return null;
        }
        List<float[]> out = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += BATCH_LIMIT) {
            List<String> batch = texts.subList(from, Math.min(texts.size(), from + BATCH_LIMIT));
            List<float[]> vectors = embedBatch(batch);
            if (vectors == null) {
                return null;
            }
            out.addAll(vectors);
        }
        return out;
    }

    /** 单条便捷入口；失败返回 null */
    public float[] embedOne(String text) {
        List<float[]> vectors = embedAll(List.of(text == null ? "" : text));
        return vectors == null || vectors.isEmpty() ? null : vectors.get(0);
    }

    private List<float[]> embedBatch(List<String> batch) {
        try {
            return parseBatch(request(batch, true), batch.size());
        } catch (Exception e) {
            // 维度是**可选参数**（qwen3.7-text-embedding-flash 默认就是 1024 维），万一某个模型/端点
            // 不认 `dimensions` 会直接 400 —— 去掉它再试一次，别因为一个可选参数把整条记忆链路降级掉
            if (dimensions > 0) {
                try {
                    log.warn("带 dimensions 的向量请求失败（{}），去掉该参数重试一次", e.getMessage());
                    return parseBatch(request(batch, false), batch.size());
                } catch (Exception retry) {
                    log.warn("取向量失败（本次按无向量处理）: {}", retry.getMessage());
                    return null;
                }
            }
            log.warn("取向量失败（本次按无向量处理）: {}", e.getMessage());
            return null;
        }
    }

    /** 发一次请求；{@code withDimensions=false} 时不带 {@code dimensions} 参数 */
    private String request(List<String> batch, boolean withDimensions) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);
        ArrayNode input = payload.putArray("input");
        for (String text : batch) {
            input.add(truncate(text));
        }
        if (withDimensions && dimensions > 0) {
            payload.put("dimensions", dimensions);
        }
        payload.put("encoding_format", "float");
        return restClient.post()
                .uri("/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload.toString())
                .retrieve()
                .body(String.class);
    }

    private List<float[]> parseBatch(String resp, int expected) throws Exception {
        JsonNode root = objectMapper.readTree(resp);
        JsonNode data = root.path("data");
        if (!data.isArray() || data.size() != expected) {
            log.warn("向量接口返回条数不符：期望 {} 实际 {}，本次跳过", expected, data.size());
            return null;
        }
        // 官方文档说 data 按 index 排序返回，但按 index 显式对齐更稳（顺序错位会串事实，代价很大）
        List<JsonNode> items = new ArrayList<>();
        data.forEach(items::add);
        items.sort(Comparator.comparingInt(n -> n.path("index").asInt()));
        List<float[]> vectors = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            JsonNode embedding = item.path("embedding");
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            vectors.add(vector);
        }
        return vectors;
    }

    private String truncate(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            // 空串会被接口拒掉（400），给一个占位，保证批量里其它条目还能过
            value = "（空）";
        }
        return value.length() <= MAX_INPUT_CHARS ? value : value.substring(0, MAX_INPUT_CHARS);
    }
}
