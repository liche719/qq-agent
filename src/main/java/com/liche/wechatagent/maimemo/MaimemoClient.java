package com.liche.wechatagent.maimemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 墨墨背单词开放 API 客户端。
 *
 * <p>接口地址 {@code https://open.maimemo.com/open/api/v1}，鉴权用 {@code Authorization: Bearer <个人 access token>}
 * （Token 在墨墨 App 的「开放 API」入口里生成）。返回值统一是 {@code {success, data, errors}}，
 * 这里把 {@code errors} 拍平成异常，调用方只需处理异常与数据。
 *
 * <p>官方限流：10 秒 20 次 / 60 秒 40 次 / 5 小时 2000 次，所以上层（{@link MaimemoService}）带缓存，
 * 不要在轮询接口里直连。
 */
@Component
public class MaimemoClient {

    private static final Logger log = LoggerFactory.getLogger(MaimemoClient.class);

    /** 今日学习进度：finished/total 为"今日任务"的完成情况 */
    public record Progress(int finished, int total, int studyTimeSeconds) {
        public int remaining() {
            return Math.max(0, total - finished);
        }

        public int percent() {
            return total <= 0 ? 0 : (int) Math.round(finished * 100.0 / total);
        }
    }

    /** 今日学习单词 */
    public record TodayItem(String spelling, int order, boolean isNew, boolean isFinished, String firstResponse) {
    }

    /** 学习记录（长期） */
    public record StudyRecord(String spelling, String nextStudyDate, String lastResponse, int studyCount,
                              List<String> tags) {
        public boolean sticking() {
            return tags != null && tags.contains("STICKING");
        }
    }

    /** Token 无效或已过期（401） */
    public static class MaimemoAuthException extends RuntimeException {
        public MaimemoAuthException(String message) {
            super(message);
        }
    }

    private final String baseUrl;
    private final int connectTimeoutSeconds;
    private final int readTimeoutSeconds;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MaimemoClient(@Value("${maimemo.base-url:https://open.maimemo.com/open/api/v1}") String baseUrl,
                         @Value("${maimemo.connect-timeout-seconds:5}") int connectTimeoutSeconds,
                         @Value("${maimemo.timeout-seconds:10}") int readTimeoutSeconds) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.connectTimeoutSeconds = bounded(connectTimeoutSeconds, 1, 60, 5);
        this.readTimeoutSeconds = bounded(readTimeoutSeconds, 1, 120, 10);
    }

    public Progress progress(String token) {
        JsonNode data = post(token, "/study/get_study_progress", objectMapper.createObjectNode());
        JsonNode progress = data.path("progress");
        return new Progress(
                progress.path("finished").asInt(0),
                progress.path("total").asInt(0),
                progress.path("study_time").asInt(0));
    }

    public List<TodayItem> todayItems(String token, int limit) {
        int size = bounded(limit, 1, 1000, 50);
        ObjectNode body = objectMapper.createObjectNode();
        body.putArray("voc_ids");
        body.putArray("spellings");
        body.put("limit", size);
        JsonNode data = post(token, "/study/get_today_items", body);
        List<TodayItem> items = new ArrayList<>();
        for (JsonNode node : data.path("today_items")) {
            items.add(new TodayItem(
                    node.path("voc_spelling").asText(""),
                    node.path("order").asInt(0),
                    node.path("is_new").asBoolean(false),
                    node.path("is_finished").asBoolean(false),
                    text(node, "first_response")));
        }
        return items;
    }

    public List<StudyRecord> records(String token, int limit) {
        int size = bounded(limit, 1, 1000, 50);
        ObjectNode body = objectMapper.createObjectNode();
        body.putArray("voc_ids");
        body.putArray("spellings");
        body.put("as_count", false);
        body.put("limit", size);
        JsonNode data = post(token, "/study/query_study_records", body);
        List<StudyRecord> records = new ArrayList<>();
        for (JsonNode node : data.path("records")) {
            List<String> tags = new ArrayList<>();
            for (JsonNode tag : node.path("tags")) {
                tags.add(tag.asText(""));
            }
            records.add(new StudyRecord(
                    node.path("voc_spelling").asText(""),
                    text(node, "next_study_date"),
                    text(node, "last_response"),
                    node.path("study_count").asInt(0),
                    tags));
        }
        return records;
    }

    /** 校验 Token 是否可用：能拿到进度即视为有效 */
    public Progress verify(String token) {
        return progress(token);
    }

    private JsonNode post(String token, String path, Object body) {
        if (token == null || token.isBlank()) {
            throw new MaimemoAuthException("尚未配置墨墨 Token");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        RestClient client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();

        String json;
        try {
            json = client.post()
                    .uri(path)
                    .header("Authorization", "Bearer " + token.trim())
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (org.springframework.web.client.HttpClientErrorException.Unauthorized exception) {
            throw new MaimemoAuthException("墨墨 Token 无效或已过期，请在 App 里重新生成后更新");
        } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests exception) {
            throw new RuntimeException("墨墨接口请求过于频繁，稍后再试");
        } catch (org.springframework.web.client.RestClientResponseException exception) {
            throw new RuntimeException("墨墨接口返回 " + exception.getStatusCode().value() + "：" + brief(exception.getResponseBodyAsString()));
        } catch (RuntimeException exception) {
            throw new RuntimeException("墨墨接口不可达：" + exception.getMessage());
        }

        try {
            JsonNode root = objectMapper.readTree(json == null ? "{}" : json);
            JsonNode errors = root.path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                throw new RuntimeException("墨墨接口报错：" + errorText(errors.get(0)));
            }
            if (!root.path("success").asBoolean(true)) {
                throw new RuntimeException("墨墨接口未成功返回");
            }
            return root.path("data");
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            log.warn("墨墨接口返回解析失败: {}", brief(json));
            throw new RuntimeException("墨墨接口返回解析失败");
        }
    }

    private String errorText(JsonNode error) {
        if (error == null || error.isNull()) {
            return "未知错误";
        }
        String code = error.path("code").asText("");
        String message = error.path("message").asText("");
        return code.isEmpty() ? message : code + " " + message;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private static String brief(String value) {
        if (value == null) {
            return "";
        }
        String flat = value.replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }

    private static String trimTrailingSlash(String value) {
        String text = value == null ? "" : value.trim();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private static int bounded(int value, int minimum, int maximum, int fallback) {
        return value < minimum || value > maximum ? fallback : value;
    }
}
