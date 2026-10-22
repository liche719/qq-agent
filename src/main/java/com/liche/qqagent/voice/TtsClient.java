package com.liche.qqagent.voice;

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
 * 语音合成（2026-10-21）—— 把一句话变成 QQ 能直接发的语音地址。
 *
 * <p>为什么只返回 URL、不下载：QQ 的语音发送接口允许直接传一个公网 URL
 * （{@code file_type=3} + {@code url}），由 QQ 自己去下载转码。这样我们不用落临时文件、
 * 不用转 silk，也不用给服务器开公网目录。
 *
 * <p>实测（2026-10-21，DashScope 北京）：
 * {@code qwen3-tts-flash} 返回 {@code {output:{audio:{url}}}}, url 是带签名的 OSS 地址，
 * <b>24 小时有效</b>，所以必须"合成完立刻发"，不能存起来下次用。
 * 计费按字符：返回里的 {@code usage.characters}。
 */
@Service
public class TtsClient {

    private static final Logger log = LoggerFactory.getLogger(TtsClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String voice;
    private final String languageType;
    private final int maxChars;
    private final int timeoutSeconds;
    private final HttpClient http;

    public TtsClient(
            @Value("${voice.tts.enabled:true}") boolean enabled,
            @Value("${voice.tts.base-url:https://dashscope.aliyuncs.com/api/v1}") String baseUrl,
            @Value("${voice.tts.api-key:${EMBEDDING_API_KEY:}}") String apiKey,
            @Value("${voice.tts.model:qwen3-tts-flash}") String model,
            @Value("${voice.tts.voice:Bella}") String voice,
            @Value("${voice.tts.language-type:Chinese}") String languageType,
            @Value("${voice.tts.max-chars:120}") int maxChars,
            @Value("${voice.tts.timeout-seconds:45}") int timeoutSeconds) {
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
        this.voice = voice == null ? "" : voice.trim();
        this.languageType = languageType == null ? "" : languageType.trim();
        this.maxChars = Math.max(20, Math.min(600, maxChars));
        this.timeoutSeconds = Math.max(10, Math.min(120, timeoutSeconds));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** 开关打开、并且地址/密钥/模型/音色都配了才算可用 */
    public boolean enabled() {
        return enabled && !baseUrl.isEmpty() && !apiKey.isEmpty() && !model.isEmpty() && !voice.isEmpty();
    }

    public int maxChars() {
        return maxChars;
    }

    public String voice() {
        return voice;
    }

    /**
     * 合成一句话，返回可交给 QQ 的音频地址。
     *
     * @param text 要念出来的话（调用方负责先清理成口语文本）
     */
    public Result synthesize(String text) {
        if (!enabled()) {
            return Result.failed("语音合成没配置。");
        }
        String speakable = text == null ? "" : text.trim();
        if (speakable.isEmpty()) {
            return Result.failed("没有要念的内容。");
        }
        int chars = speakable.codePointCount(0, speakable.length());
        if (chars > maxChars) {
            return Result.failed("要念的字太多（" + chars + " 字，上限 " + maxChars + "）。");
        }
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("text", speakable);
            input.put("voice", voice);
            if (!languageType.isEmpty()) {
                input.put("language_type", languageType);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("input", input);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "")
                            + "/services/aigc/multimodal-generation/generation"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();

            long started = System.currentTimeMillis();
            // 超时重试一次（2026-10-22）：阿里云 TTS 实测偶尔 30 秒不返回，
            // 一次失败就整条语音没了；重试命中率高，代价只是多等一会儿。
            HttpResponse<String> response = null;
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    response = http.send(request, HttpResponse.BodyHandlers.ofString());
                    break;
                } catch (java.net.http.HttpTimeoutException timeout) {
                    if (attempt == 2) {
                        throw timeout;
                    }
                    log.warn("语音合成超时，重试一次 model={} timeout={}s", model, timeoutSeconds);
                }
            }
            long costMs = System.currentTimeMillis() - started;
            if (response == null) {
                return Result.failed("语音合成没拿到响应。");
            }

            if (response.statusCode() != 200) {
                // 错误体可能回显请求，只留前 200 字便于定位
                log.warn("语音合成失败 model={} status={} body={}", model, response.statusCode(),
                        clip(response.body(), 200));
                return Result.failed("语音接口返回 " + response.statusCode());
            }
            JsonNode root = JSON.readTree(response.body());
            String url = root.path("output").path("audio").path("url").asText("");
            if (url.isBlank()) {
                return Result.failed("语音接口没给音频地址。");
            }
            int billedChars = root.path("usage").path("characters").asInt(chars);
            log.info("语音合成完成 voice={} 字数={} 计费字符={} 用时={}ms", voice, chars, billedChars, costMs);
            return new Result(true, url, billedChars, costMs);
        } catch (Exception e) {
            log.warn("语音合成出错：{}", e.toString());
            return Result.failed("语音合成出错（" + clip(e.getMessage(), 100) + "）");
        }
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    /** 合成结果：成没成 / 音频地址 / 计费字符数 / 耗时 */
    public record Result(boolean ok, String url, int characters, long costMs) {

        static Result failed(String why) {
            return new Result(false, why, 0, 0);
        }
    }
}
