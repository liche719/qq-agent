package com.liche.wechatagent.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liche.wechatagent.metrics.RuntimeMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** SearX-NG 搜索引擎客户端（自托管实例，JSON 输出格式） */
@Component
public class SearxngClient {

    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 5;
    private static final String DEFAULT_LANGUAGE = "zh-CN";

    public record SearchHit(String url, String title, String content, String publishedDate) {
    }

    private final String baseUrl;
    private final int connectTimeoutSeconds;
    private final String language;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 指标采集，可为 null（单元测试直接构造时不需要） */
    private final RuntimeMetrics metrics;

    @Autowired
    public SearxngClient(@Value("${searxng.base-url}") String baseUrl,
                         @Value("${searxng.connect-timeout-seconds:5}") int connectTimeoutSeconds,
                         @Value("${searxng.language:zh-CN}") String language,
                         RuntimeMetrics metrics) {
        this.baseUrl = baseUrl;
        this.connectTimeoutSeconds = bounded(connectTimeoutSeconds, 1, 120, DEFAULT_CONNECT_TIMEOUT_SECONDS);
        this.language = language == null || language.isBlank() ? DEFAULT_LANGUAGE : language.trim();
        this.metrics = metrics;
    }

    public SearxngClient(String baseUrl) {
        this(baseUrl, DEFAULT_CONNECT_TIMEOUT_SECONDS, DEFAULT_LANGUAGE, null);
    }

    public List<SearchHit> search(String query, int timeoutSeconds) {
        return search(query, timeoutSeconds, null);
    }

    public List<SearchHit> search(String query, int timeoutSeconds, String timeRange) {
        long started = System.nanoTime();
        try {
            List<SearchHit> hits = doSearch(query, timeoutSeconds, timeRange);
            record(true, hits.size(), started, null);
            return hits;
        } catch (RuntimeException exception) {
            record(false, 0, started, exception.getMessage());
            throw exception;
        }
    }

    private List<SearchHit> doSearch(String query, int timeoutSeconds, String timeRange) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        RestClient client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();

        String json = client.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/search")
                            .queryParam("q", query)
                            .queryParam("format", "json")
                            .queryParam("language", language);
                    if (timeRange != null && !timeRange.isBlank()) {
                        uriBuilder.queryParam("time_range", timeRange);
                    }
                    return uriBuilder.build();
                })
                .retrieve()
                .body(String.class);

        try {
            JsonNode root = objectMapper.readTree(json);
            List<SearchHit> hits = new ArrayList<>();
            for (JsonNode r : root.path("results")) {
                hits.add(new SearchHit(
                        r.path("url").asText(""),
                        r.path("title").asText(""),
                        r.path("content").asText(""),
                        r.path("publishedDate").asText("")));
            }
            return hits;
        } catch (Exception e) {
            throw new RuntimeException("SearX-NG 返回解析失败", e);
        }
    }

    private void record(boolean ok, int hitCount, long startedNanos, String error) {
        if (metrics != null) {
            metrics.recordSearch(ok, hitCount, Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L), error);
        }
    }

    private int bounded(int value, int minimum, int maximum, int fallback) {
        return value < minimum || value > maximum ? fallback : value;
    }
}
