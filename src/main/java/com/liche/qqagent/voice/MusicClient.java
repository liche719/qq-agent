package com.liche.qqagent.voice;

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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 音乐生成（2026-11-03）—— 让笨鱼真的"唱"一首，而不是把歌词念出来。
 *
 * <p><b>为什么单独一个客户端</b>：TTS 只给"语调"，唱歌要的是"旋律"，两者是不同的模型。
 * 阿里云百炼的 {@code fun-music-v1} 能按歌词谱曲并有人声演唱，返回一个 24 小时有效的 mp3 地址，
 * 正好能接进现有的 {@code sendVoiceReplyFrom(audioUrl)}。
 *
 * <p><b>现状</b>：该模型处于**邀测**阶段（仅华北2北京，且要用 WorkspaceId 专用端点）。
 * 所以我们默认关闭；配好 {@code voice.music.base-url} 与开关后即生效。
 * 调用失败（未开通/额度不足/超时）时，调用方要**优雅降级**成"念歌词"，并如实告诉用户没唱成。
 */
@Component
public class MusicClient {

    private static final Logger log = LoggerFactory.getLogger(MusicClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String gender;
    private final int maxLyricsChars;
    private final int timeoutSeconds;
    private final HttpClient http;

    public MusicClient(
            @Value("${voice.music.enabled:false}") boolean enabled,
            @Value("${voice.music.base-url:}") String baseUrl,
            @Value("${voice.music.api-key:${DASHSCOPE_API_KEY:}}") String apiKey,
            @Value("${voice.music.model:fun-music-v1}") String model,
            @Value("${voice.music.gender:female}") String gender,
            @Value("${voice.music.max-lyrics-chars:350}") int maxLyricsChars,
            @Value("${voice.music.timeout-seconds:120}") int timeoutSeconds) {
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
        this.gender = gender == null ? "" : gender.trim();
        this.maxLyricsChars = Math.max(20, Math.min(350, maxLyricsChars));
        this.timeoutSeconds = Math.max(30, Math.min(300, timeoutSeconds));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** 开关打开 + 地址与密钥都配了才算可用（邀测没开通时这里就是 false） */
    public boolean enabled() {
        return enabled && !baseUrl.isEmpty() && !apiKey.isEmpty() && !model.isEmpty();
    }

    public int maxLyricsChars() {
        return maxLyricsChars;
    }

    public record Result(boolean ok, String url, int durationSeconds, long costMs, String error) {
        static Result failed(String error) {
            return new Result(false, "", 0, 0, error);
        }
    }

    /**
     * 按歌词生成一首歌。
     *
     * @param lyrics 歌词（中文 5~350 字；带 [verse]/[chorus] 这类标记更好）
     * @param style  风格提示词，如"温柔民谣，木吉他，慢速"
     */
    public Result sing(String lyrics, String style) {
        if (!enabled()) {
            return Result.failed("音乐生成没配置（邀测未开通或没配地址）。");
        }
        String text = lyrics == null ? "" : lyrics.trim();
        if (text.isEmpty()) {
            return Result.failed("没有歌词。");
        }
        int chars = text.codePointCount(0, text.length());
        if (chars > maxLyricsChars) {
            return Result.failed("歌词太长（" + chars + " 字，上限 " + maxLyricsChars + "）。");
        }
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("lyrics", text);
            if (style != null && !style.isBlank()) {
                input.put("prompt", style.trim());
            }
            if (!gender.isEmpty()) {
                input.put("gender", gender);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("input", input);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "")
                            + "/api/v1/services/audio/music/generation"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();

            long started = System.currentTimeMillis();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            long costMs = System.currentTimeMillis() - started;
            if (response.statusCode() != 200) {
                log.warn("音乐生成失败 model={} status={} body={}", model, response.statusCode(),
                        clip(response.body(), 200));
                return Result.failed("音乐接口返回 " + response.statusCode());
            }
            JsonNode root = JSON.readTree(response.body());
            String url = root.path("output").path("audio").path("url").asText("");
            int duration = root.path("usage").path("duration").asInt(0);
            if (url.isBlank()) {
                return Result.failed("音乐接口没给音频地址。");
            }
            log.info("音乐生成完成 model={} 歌词={}字 时长={}s 用时={}ms", model, chars, duration, costMs);
            return new Result(true, url, duration, costMs, "");
        } catch (java.net.http.HttpTimeoutException timeout) {
            return Result.failed("音乐生成超时（" + timeoutSeconds + " 秒）。");
        } catch (Exception e) {
            log.warn("音乐生成异常 model={} error={}", model, e.toString());
            return Result.failed("音乐生成失败：" + clip(e.toString(), 80));
        }
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
