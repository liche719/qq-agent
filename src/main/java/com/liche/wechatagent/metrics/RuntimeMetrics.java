package com.liche.wechatagent.metrics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 运行期指标：LLM 调用与搜索的成功/失败、耗时、最近一次错误。
 *
 * <p>面板用这些数字回答「机器人为什么变慢/变傻」——出问题是模型慢、模型报错，
 * 还是搜索挂了，不用再去翻日志。
 */
@Component
public class RuntimeMetrics {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    private final ZoneId zone;

    private final AtomicLong llmCalls = new AtomicLong();
    private final AtomicLong llmFailures = new AtomicLong();
    private final AtomicLong llmTotalMillis = new AtomicLong();
    private final AtomicLong llmLastMillis = new AtomicLong();
    private volatile String lastLlmError = "";
    private volatile String lastLlmErrorAt = "";

    private final AtomicLong streamCalls = new AtomicLong();
    private final AtomicLong streamFailures = new AtomicLong();
    private final AtomicLong streamTotalMillis = new AtomicLong();
    private final AtomicLong streamLastMillis = new AtomicLong();

    private final AtomicLong searchCalls = new AtomicLong();
    private final AtomicLong searchFailures = new AtomicLong();
    private final AtomicLong searchEmpty = new AtomicLong();
    private final AtomicLong searchTotalMillis = new AtomicLong();
    private final AtomicLong searchLastMillis = new AtomicLong();
    private volatile String lastSearchError = "";
    private volatile String lastSearchErrorAt = "";

    public RuntimeMetrics(@Value("${app.time-zone:Asia/Shanghai}") String timeZone) {
        this.zone = parseZone(timeZone);
    }

    /** 记录一次 LLM 调用（streaming=true 表示对话回复用的流式调用） */
    public void recordLlm(boolean streaming, boolean ok, long millis, String error) {
        if (streaming) {
            streamCalls.incrementAndGet();
            streamTotalMillis.addAndGet(millis);
            streamLastMillis.set(millis);
            if (!ok) {
                streamFailures.incrementAndGet();
            }
        } else {
            llmCalls.incrementAndGet();
            llmTotalMillis.addAndGet(millis);
            llmLastMillis.set(millis);
            if (!ok) {
                llmFailures.incrementAndGet();
            }
        }
        if (!ok && error != null && !error.isBlank()) {
            lastLlmError = trim(error);
            lastLlmErrorAt = now();
        }
    }

    /** 记录一次搜索调用 */
    public void recordSearch(boolean ok, int hitCount, long millis, String error) {
        searchCalls.incrementAndGet();
        searchTotalMillis.addAndGet(millis);
        searchLastMillis.set(millis);
        if (!ok) {
            searchFailures.incrementAndGet();
        } else if (hitCount == 0) {
            searchEmpty.incrementAndGet();
        }
        if (!ok && error != null && !error.isBlank()) {
            lastSearchError = trim(error);
            lastSearchErrorAt = now();
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("llm", section(llmCalls.get(), llmFailures.get(), llmTotalMillis.get(), llmLastMillis.get(),
                lastLlmError, lastLlmErrorAt));
        Map<String, Object> stream = section(streamCalls.get(), streamFailures.get(), streamTotalMillis.get(),
                streamLastMillis.get(), "", "");
        stream.put("说明", "对话回复的流式调用");
        out.put("llmStream", stream);
        Map<String, Object> search = section(searchCalls.get(), searchFailures.get(), searchTotalMillis.get(),
                searchLastMillis.get(), lastSearchError, lastSearchErrorAt);
        search.put("emptyResults", searchEmpty.get());
        out.put("search", search);
        return out;
    }

    private Map<String, Object> section(long calls, long failures, long totalMillis, long lastMillis,
                                        String lastError, String lastErrorAt) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("calls", calls);
        section.put("failures", failures);
        section.put("successRate", calls == 0 ? null : Math.round((calls - failures) * 100.0 / calls));
        section.put("averageMs", calls == 0 ? null : Math.round((double) totalMillis / calls));
        section.put("lastMs", lastMillis == 0 ? null : lastMillis);
        if (lastError != null && !lastError.isBlank()) {
            section.put("lastError", lastError);
            section.put("lastErrorAt", lastErrorAt);
        }
        return section;
    }

    private String now() {
        return LocalDateTime.now(zone).format(TIME);
    }

    private static String trim(String value) {
        String text = value.replace('\n', ' ').trim();
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }

    private static ZoneId parseZone(String value) {
        try {
            return ZoneId.of(value);
        } catch (RuntimeException ignored) {
            return ZoneId.of("Asia/Shanghai");
        }
    }
}
