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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 直接看视频（2026-10-21）—— 把视频交给支持视频输入的模型，让它自己看画面。
 *
 * <p>为什么要有这个：原来的做法是「没字幕就下载 360p → 用 ffmpeg 抽帧 → 拼成联系表 →
 * 交给视觉模型看几张图」。抽帧的毛病是**只能看到静态切片**：字幕飞过去、动作连续变化、
 * 一句话配一个画面，抽 4 秒一帧基本看不出来。现在上游有模型能直接吃视频（实测
 * {@code qwen3.8-flash} 可以），那就把整段视频交给它，画面和节奏它自己看。
 *
 * <p><b>为什么不用 LangChain4j</b>：视频输入不是 OpenAI 的标准字段，各家写法不一，
 * 这里要能精确控制请求体（也方便以后换格式），所以直接走 HttpClient，只复用一个 base-url 与 key。
 *
 * <p><b>体积</b>：实测 5 秒 360p 视频 = 3680 prompt token（约 1.4MB base64）。
 * 视频越大 token 越多、请求体也越大，所以这里设了 base64 上限，超了就让调用方回落到抽帧。
 */
@Service
public class VideoUnderstandService {

    private static final Logger log = LoggerFactory.getLogger(VideoUnderstandService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final long maxBase64Bytes;
    private final int maxOutputTokens;
    private final HttpClient http;
    /** 审计出口（2026-10-21）：视频调用以前完全不入账，面板上看不到这笔钱 */
    private final com.liche.qqagent.config.LlmCallSink auditSink;

    public VideoUnderstandService(
            @Value("${llm.base-url:}") String baseUrl,
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.video-model:}") String model,
            @Value("${llm.video-max-base64-bytes:16777216}") long maxBase64Bytes,
            @Value("${llm.video-max-output-tokens:4000}") int maxOutputTokens,
            org.springframework.beans.factory.ObjectProvider<com.liche.qqagent.config.LlmCallSink> auditSink) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
        this.maxBase64Bytes = maxBase64Bytes;
        this.maxOutputTokens = maxOutputTokens;
        this.auditSink = auditSink.getIfAvailable();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    /** 配了模型才启用；没配就整条路走老逻辑（抽帧） */
    public boolean enabled() {
        return !model.isEmpty() && !baseUrl.isEmpty() && !apiKey.isEmpty();
    }

    /** 看一段视频，返回它的文字描述 */
    public Result watch(Path videoFile, String what) {
        if (!enabled()) {
            return Result.failed("没配视频模型，这次没看画面。");
        }
        if (videoFile == null || !Files.isRegularFile(videoFile)) {
            return Result.failed("视频文件不在。");
        }
        try {
            long size = Files.size(videoFile);
            long estimate = size / 3 * 4;   // base64 大约 4/3
            if (estimate > maxBase64Bytes) {
                return Result.failed("视频太大（" + (size / 1024 / 1024) + "MB，base64 后约 "
                        + (estimate / 1024 / 1024) + "MB，上限 " + (maxBase64Bytes / 1024 / 1024) + "MB），没直接看。");
            }
            String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(videoFile));
            String prompt = what == null || what.isBlank()
                    ? "把这段视频看一遍，用中文说清楚：画面里有什么、在发生什么、有没有台词或字幕。"
                    + "只说你真的看到的，看不清就说看不清，不要脑补。"
                    : what;

            // 用显式 LinkedHashMap 而不是嵌套 Map.of：Map.of 在这里的类型推断会失败
            // （List<Map<String,Object>> 与 List<Map<String,String>> 推不到一处）
            Map<String, Object> textPart = new LinkedHashMap<>();
            textPart.put("type", "text");
            textPart.put("text", prompt);

            Map<String, Object> videoUrl = new LinkedHashMap<>();
            videoUrl.put("url", "data:video/mp4;base64," + base64);

            Map<String, Object> videoPart = new LinkedHashMap<>();
            videoPart.put("type", "video_url");
            videoPart.put("video_url", videoUrl);

            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "user");
            message.put("content", List.of(textPart, videoPart));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", List.of(message));
            body.put("max_tokens", maxOutputTokens);
            // 视频描述不需要深度推理（实测上游不一定完全关掉，所以额度也一并给足）
            body.put("reasoning_effort", "none");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions"))
                    .timeout(Duration.ofMinutes(4))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();

            long started = System.currentTimeMillis();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            long costMs = System.currentTimeMillis() - started;

            if (response.statusCode() != 200) {
                log.warn("视频理解失败 model={} status={} body={}", model, response.statusCode(),
                        clip(response.body(), 300));
                return Result.failed("视频模型返回 " + response.statusCode() + "（" + clip(response.body(), 120) + "）");
            }
            JsonNode root = JSON.readTree(response.body());
            JsonNode choice = root.path("choices").path(0).path("message");
            String text = choice.path("content").asText("").trim();
            if (text.isEmpty()) {
                return Result.failed("视频模型没说话（返回是空的）。");
            }
            JsonNode usage = root.path("usage");
            int promptTokens = usage.path("prompt_tokens").asInt();
            int completionTokens = usage.path("completion_tokens").asInt();
            int reasoningTokens = usage.path("completion_tokens_details").path("reasoning_tokens").asInt();
            int cachedTokens = usage.path("prompt_tokens_details").path("cached_tokens").asInt();
            log.info("视频理解完成 model={} 用时={}ms 输入={} 输出={} 思考={}",
                    model, costMs, promptTokens, completionTokens, reasoningTokens);
            if (auditSink != null) {
                try {
                    auditSink.acceptCall(new com.liche.qqagent.config.LlmCallEvent(
                            "video", false, true, costMs,
                            new com.liche.qqagent.config.LlmUsage(
                                    promptTokens, completionTokens, reasoningTokens, cachedTokens), null));
                } catch (RuntimeException ignored) {
                    // 记账失败不能影响看视频
                }
            }
            return new Result(true, text, promptTokens, costMs);
        } catch (Exception e) {
            log.warn("视频理解出错：{}", e.toString());
            return Result.failed("视频模型调用失败（" + clip(e.getMessage(), 100) + "）");
        }
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    /** 看视频的结果：成没成 / 它说了什么 / 花了多少 token / 用了多久 */
    public record Result(boolean ok, String text, int promptTokens, long costMs) {

        static Result failed(String reason) {
            return new Result(false, reason, 0, 0);
        }
    }
}
