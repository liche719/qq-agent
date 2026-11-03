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
 * 生图客户端（2026-11-03 从"基元代理"换成"阿里云百炼原生"）。
 *
 * <p><b>为什么换</b>：基元那边图片是未列出的隐藏通道，只有 {@code qwen-image-2.0} 一个模型、
 * 5 个参数（model/prompt/n/size/response_format），**没有 negative_prompt、没有 seed**。
 * 百炼原生多了 negative_prompt / seed / prompt_extend，而且更便宜（qwen-image-3.0 0.18 元/张）。
 *
 * <p><b>两套协议</b>（同一个 key，不同端点）：
 * <ul>
 *   <li>{@code qwen-image-*} → multimodal 端点，**同步**直接返回图片 URL；</li>
 *   <li>{@code wanx2.1-*} → text2image 端点，**异步任务**（提交拿 task_id，再轮询）。</li>
 * </ul>
 * 尺寸格式也和基元不同：百炼是 {@code 1024*1024}（星号），基元是 {@code 1024x1024}。
 */
@Service
public class ImageGenClient {

    private static final Logger log = LoggerFactory.getLogger(ImageGenClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    /** prompt 自动扩写：百炼默认开，实测能显著提升出图质量 */
    private final boolean promptExtend;
    private final int timeoutSeconds;
    private final HttpClient http;

    public ImageGenClient(
            @Value("${llm.image-base-url:https://dashscope.aliyuncs.com}") String baseUrl,
            @Value("${llm.image-api-key:${DASHSCOPE_API_KEY:}}") String apiKey,
            @Value("${llm.image-model:qwen-image-3.0}") String model,
            @Value("${llm.image-prompt-extend:true}") boolean promptExtend,
            @Value("${llm.image-timeout-seconds:180}") int timeoutSeconds) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
        this.promptExtend = promptExtend;
        this.timeoutSeconds = Math.max(30, Math.min(300, timeoutSeconds));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    /** 配了模型和密钥才可用 */
    public boolean enabled() {
        return !model.isEmpty() && !baseUrl.isEmpty() && !apiKey.isEmpty();
    }

    /** 允许的尺寸范围（百炼实测：宽高 512~4096，格式 `宽*高`） */
    public static boolean validSize(String size) {
        if (size == null) {
            return false;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(\\d{3,4})[x*](\\d{3,4})$").matcher(size.trim());
        if (!matcher.matches()) {
            return false;
        }
        int width = Integer.parseInt(matcher.group(1));
        int height = Integer.parseInt(matcher.group(2));
        return width >= 512 && width <= 4096 && height >= 512 && height <= 4096;
    }

    public record Result(boolean ok, String url, String imageId, long costMs) {
        static Result failed(String url) {
            return new Result(false, url, "", 0);
        }
    }

    /** 生成一张图（默认 1024x1024 方图） */
    public Result generate(String prompt) {
        return generate(prompt, null);
    }

    public Result generate(String prompt, String size) {
        if (!enabled()) {
            return Result.failed("没配生图模型。");
        }
        if (prompt == null || prompt.isBlank()) {
            return Result.failed("没说要画什么。");
        }
        String dashSize = dashScopeSize(size);
        try {
            return model.startsWith("wanx") ? generateAsync(prompt, dashSize) : generateSync(prompt, dashSize);
        } catch (Exception e) {
            log.warn("生图出错 model={} error={}", model, e.toString());
            return Result.failed("生图失败（" + clip(e.getMessage(), 100) + "）");
        }
    }

    /** qwen-image 系列：multimodal 端点，同步返回 */
    private Result generateSync(String prompt, String size) throws Exception {
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("text", prompt);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", java.util.List.of(text));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("messages", java.util.List.of(message));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("n", 1);
        parameters.put("size", size);
        parameters.put("prompt_extend", promptExtend);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", input);
        body.put("parameters", parameters);

        long started = System.currentTimeMillis();
        HttpResponse<String> response = post("/api/v1/services/aigc/multimodal-generation/generation", body);
        long costMs = System.currentTimeMillis() - started;
        if (response.statusCode() != 200) {
            log.warn("生图失败 model={} status={} body={}", model, response.statusCode(), clip(response.body(), 300));
            return Result.failed("生图接口返回 " + response.statusCode());
        }
        JsonNode root = JSON.readTree(response.body());
        String url = root.path("output").path("choices").path(0).path("message").path("content")
                .path(0).path("image").asText("");
        if (url.isBlank()) {
            return Result.failed("生图接口没给图片地址。");
        }
        log.info("生图完成 model={} 用时={}ms", model, costMs);
        return new Result(true, url, "", costMs);
    }

    /** wanx 系列：提交任务 → 轮询 */
    private Result generateAsync(String prompt, String size) throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("prompt", prompt);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("size", size);
        parameters.put("n", 1);
        parameters.put("prompt_extend", promptExtend);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", input);
        body.put("parameters", parameters);

        long started = System.currentTimeMillis();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl.replaceAll("/+$", "")
                        + "/api/v1/services/aigc/text2image/image-synthesis"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("X-DashScope-Async", "enable")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("生图任务提交失败 model={} status={} body={}", model, response.statusCode(),
                    clip(response.body(), 300));
            return Result.failed("生图任务提交失败（" + response.statusCode() + "）");
        }
        String taskId = JSON.readTree(response.body()).path("output").path("task_id").asText("");
        if (taskId.isBlank()) {
            return Result.failed("生图任务没给 task_id。");
        }
        // 轮询：实测 turbo 约 9 秒，给足余量
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(3000);
            HttpRequest query = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/api/v1/tasks/" + taskId))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();
            HttpResponse<String> poll = http.send(query, HttpResponse.BodyHandlers.ofString());
            JsonNode output = JSON.readTree(poll.body()).path("output");
            String status = output.path("task_status").asText("");
            if ("SUCCEEDED".equals(status)) {
                String url = output.path("results").path(0).path("url").asText("");
                if (url.isBlank()) {
                    return Result.failed("生图任务成功但没给图片地址。");
                }
                long costMs = System.currentTimeMillis() - started;
                log.info("生图完成 model={} 用时={}ms（异步）", model, costMs);
                return new Result(true, url, taskId, costMs);
            }
            if ("FAILED".equals(status)) {
                log.warn("生图任务失败 model={} body={}", model, clip(poll.body(), 300));
                return Result.failed("生图任务失败。");
            }
        }
        return Result.failed("生图超时（" + timeoutSeconds + " 秒）。");
    }

    private HttpResponse<String> post(String path, Map<String, Object> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl.replaceAll("/+$", "") + path))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** 百炼尺寸格式是 `宽*高`；我们内部统一用 `宽x高`，这里转换 */
    private static String dashScopeSize(String size) {
        return validSize(size) ? size.trim().replace('x', '*') : "1024*1024";
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
