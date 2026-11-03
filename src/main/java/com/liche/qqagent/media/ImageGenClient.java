package com.liche.qqagent.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 画图（2026-10-21）—— 调 {@code /v1/images/generations}，拿回一张图的 URL。
 *
 * <p>为什么单独一个客户端：生图是 OpenAI 的一个独立端点（不是 chat），
 * 请求/返回结构都不一样，混在 ChatModel 里没法表达。
 *
 * <p>实测（基元律动）：{@code qwen-image-2.0} 与 {@code wan2.7-image} 都是 0.2 元/张，
 * 返回 {@code {created, data:[{image_id, url}]}}，url 指向阿里云 OSS（带签名，有时效）。
 * 所以拿到就要立刻下载，别存 URL 等以后再用。
 */
@Service
public class ImageGenClient {

    private static final Logger log = LoggerFactory.getLogger(ImageGenClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int timeoutSeconds;
    private final HttpClient http;

    public ImageGenClient(
            @Value("${llm.base-url:}") String baseUrl,
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.image-model:qwen-image-2.0}") String model,
            @Value("${llm.image-timeout-seconds:120}") int timeoutSeconds) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
        this.timeoutSeconds = Math.max(20, Math.min(300, timeoutSeconds));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    /** 配了模型和密钥才可用 */
    public boolean enabled() {
        return !model.isEmpty() && !baseUrl.isEmpty() && !apiKey.isEmpty();
    }

    /** 允许的尺寸范围（代理实测：宽高都在 512~4096，格式 WxH） */
    public static boolean validSize(String size) {
        if (size == null) {
            return false;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(\\d{3,4})x(\\d{3,4})$").matcher(size.trim());
        if (!matcher.matches()) {
            return false;
        }
        int width = Integer.parseInt(matcher.group(1));
        int height = Integer.parseInt(matcher.group(2));
        return width >= 512 && width <= 4096 && height >= 512 && height <= 4096;
    }

    /** 生成一张图（默认 1024x1024 方图） */
    public Result generate(String prompt) {
        return generate(prompt, null);
    }

    /**
     * 生成一张图，可指定尺寸（2026-11-03）。
     *
     * <p>为什么要它：这个模型**只有 5 个参数**（model/prompt/n/size/response_format），
     * 没有 steps/cfg/seed/negative_prompt——尺寸是唯一能显著改变观感的旋钮。
     * 之前一直不传，所以永远出 1024×1024 方图，"画个手机壁纸/头像"构图必然是错的。
     *
     * @param size 形如 {@code 768x1344}；null 或非法值走默认
     */
    public Result generate(String prompt, String size) {
        if (!enabled()) {
            return Result.failed("没配生图模型。");
        }
        if (prompt == null || prompt.isBlank()) {
            return Result.failed("没说要画什么。");
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("prompt", prompt);
            body.put("n", 1);
            if (validSize(size)) {
                body.put("size", size.trim());
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/images/generations"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();

            long started = System.currentTimeMillis();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            long costMs = System.currentTimeMillis() - started;

            if (response.statusCode() != 200) {
                log.warn("生图失败 model={} status={} body={}", model, response.statusCode(),
                        clip(response.body(), 300));
                return Result.failed("生图接口返回 " + response.statusCode());
            }
            JsonNode root = JSON.readTree(response.body());
            JsonNode first = root.path("data").path(0);
            String url = first.path("url").asText("");
            if (url.isBlank()) {
                return Result.failed("生图接口没给图片地址。");
            }
            String imageId = first.path("image_id").asText("");
            log.info("生图完成 model={} 用时={}ms image_id={}", model, costMs, imageId);
            return new Result(true, url, imageId, costMs);
        } catch (Exception e) {
            log.warn("生图出错：{}", e.toString());
            return Result.failed("生图失败（" + clip(e.getMessage(), 100) + "）");
        }
    }

    private static String clip(String value, int max) {
        return com.liche.qqagent.support.Texts.clipWide(value, max);
    }

    /** 生图结果：成没成 / 图片地址 / 平台侧 id / 耗时 */
    public record Result(boolean ok, String url, String imageId, long costMs) {

        static Result failed(String why) {
            return new Result(false, why, "", 0);
        }
    }
}
