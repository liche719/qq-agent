package com.liche.wechatagent.maimemo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 墨墨背单词的数据入口：Token 管理、进度聚合、缓存与文案。
 *
 * <p>三处共用这一层：聊天里的工具（{@code MaimemoTool}）、面板「背单词」页、每日推送。
 * 官方限流是 10 秒 20 次 / 60 秒 40 次，所以上游结果带缓存（默认 30 秒），
 * 面板自动刷新和聊天追问都不会真的打那么勤。
 */
@Service
public class MaimemoService {

    /** 设置项键名 */
    public static final String KEY_TOKEN = "api_token";
    public static final String KEY_PUSH_ENABLED = "daily_push_enabled";
    public static final String KEY_PUSH_TIME = "daily_push_time";
    public static final String KEY_LAST_PUSH_DATE = "last_push_date";

    public static final String STATUS_OK = "OK";
    public static final String STATUS_NOT_CONFIGURED = "NOT_CONFIGURED";
    public static final String STATUS_UNAUTHORIZED = "UNAUTHORIZED";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_DISABLED = "DISABLED";

    private static final Logger log = LoggerFactory.getLogger(MaimemoService.class);
    private static final DateTimeFormatter CHECKED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter UPDATED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final MaimemoClient client;
    private final MaimemoSettingRepository settings;
    private final boolean enabled;
    private final String envToken;
    private final boolean defaultPushEnabled;
    private final LocalTime defaultPushTime;
    private final int itemLimit;
    private final long cacheMillis;
    private final ZoneId zone;

    private volatile Map<String, Object> cache;
    private volatile long cachedAtMillis;

    public MaimemoService(MaimemoClient client,
                          MaimemoSettingRepository settings,
                          @Value("${maimemo.enabled:true}") boolean enabled,
                          @Value("${maimemo.api-token:}") String envToken,
                          @Value("${maimemo.daily-push-enabled:true}") boolean defaultPushEnabled,
                          @Value("${maimemo.daily-push-time:21:30}") String defaultPushTime,
                          @Value("${maimemo.item-limit:30}") int itemLimit,
                          @Value("${maimemo.cache-seconds:30}") int cacheSeconds,
                          @Value("${app.time-zone:Asia/Shanghai}") String timeZone) {
        this.client = client;
        this.settings = settings;
        this.enabled = enabled;
        this.envToken = envToken == null ? "" : envToken.trim();
        this.defaultPushEnabled = defaultPushEnabled;
        this.defaultPushTime = parseTime(defaultPushTime, LocalTime.of(21, 30));
        this.itemLimit = Math.max(5, Math.min(200, itemLimit));
        this.cacheMillis = Math.max(0, Math.min(600, cacheSeconds)) * 1000L;
        this.zone = parseZone(timeZone);
    }

    /** 面板/工具共用的快照（带缓存） */
    public Map<String, Object> snapshot() {
        Map<String, Object> current = cache;
        if (current != null && System.currentTimeMillis() - cachedAtMillis < cacheMillis) {
            return current;
        }
        return refresh();
    }

    /** 强制拉一次上游（面板上的「立即刷新」和 Token 保存后用这个） */
    public synchronized Map<String, Object> refresh() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("checkedAt", LocalDateTime.now(zone).format(CHECKED_AT));
        result.put("tokenSource", tokenSource());
        result.put("tokenHint", maskedToken());
        result.put("tokenUpdatedAt", tokenUpdatedAt());
        result.put("push", pushState());

        String token = effectiveToken();
        if (!enabled) {
            result.put("status", STATUS_DISABLED);
            result.put("message", "墨墨接入已关闭（maimemo.enabled=false）");
            return store(result);
        }
        if (token.isBlank()) {
            result.put("status", STATUS_NOT_CONFIGURED);
            result.put("message", "还没配置墨墨 Token：在墨墨 App 的「开放 API」里生成，粘贴到本页保存即可。");
            return store(result);
        }
        try {
            MaimemoClient.Progress progress = client.progress(token);
            List<MaimemoClient.TodayItem> items = client.todayItems(token, itemLimit);
            List<MaimemoClient.StudyRecord> records = client.records(token, itemLimit);
            result.put("status", STATUS_OK);
            result.put("message", "已连接墨墨开放 API");
            result.put("progress", progressMap(progress, items));
            result.put("todayItems", itemList(items));
            result.put("records", recordList(records));
            return store(result);
        } catch (MaimemoClient.MaimemoAuthException exception) {
            result.put("status", STATUS_UNAUTHORIZED);
            result.put("message", exception.getMessage());
            return store(result);
        } catch (RuntimeException exception) {
            log.warn("读取墨墨数据失败: {}", exception.getMessage());
            result.put("status", STATUS_ERROR);
            result.put("message", exception.getMessage());
            return store(result);
        }
    }

    /** Token 失效后清缓存，让下一次读取立刻重试 */
    public void invalidate() {
        cachedAtMillis = 0L;
    }

    /** 聊天工具用的文本摘要：只讲用户关心的进度与待办 */
    public String chatSummary() {
        Map<String, Object> snapshot = snapshot();
        String status = String.valueOf(snapshot.get("status"));
        if (STATUS_NOT_CONFIGURED.equals(status) || STATUS_DISABLED.equals(status)) {
            return String.valueOf(snapshot.get("message"));
        }
        if (STATUS_UNAUTHORIZED.equals(status)) {
            return "墨墨 Token 已失效，查不到数据了（" + snapshot.get("message")
                    + "）。跟用户说明：去墨墨 App 的「开放 API」重新生成 Token，再粘贴到运维面板的「背单词」页保存，就能继续查。";
        }
        if (STATUS_ERROR.equals(status)) {
            return "现在拿不到墨墨数据：" + snapshot.get("message") + "。如实告知用户，不要猜数字。";
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> progress = (Map<String, Object>) snapshot.get("progress");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) snapshot.get("todayItems");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) snapshot.get("records");

        int finished = number(progress.get("finished"));
        int total = number(progress.get("total"));
        int remaining = number(progress.get("remaining"));
        StringBuilder sb = new StringBuilder("墨墨背单词（数据取自墨墨开放 API，查询时间 ")
                .append(snapshot.get("checkedAt")).append("）\n");
        sb.append("今日任务：已完成 ").append(finished).append("/").append(total)
                .append("（").append(number(progress.get("percent"))).append("%）");
        if (remaining > 0) {
            sb.append("，还剩 ").append(remaining).append(" 个");
        } else if (total > 0) {
            sb.append("，今天已经背完了");
        }
        sb.append("\n");
        sb.append("其中新学 ").append(number(progress.get("newCount")))
                .append(" 个、复习 ").append(number(progress.get("reviewCount")))
                .append(" 个；学习时长约 ").append(number(progress.get("studyTimeMinutes"))).append(" 分钟\n");

        List<String> pending = new ArrayList<>();
        for (Map<String, Object> item : items) {
            if (!Boolean.TRUE.equals(item.get("finished")) && pending.size() < 8) {
                pending.add(String.valueOf(item.get("spelling")));
            }
        }
        if (!pending.isEmpty()) {
            sb.append("还没背的（前面几个）：").append(String.join("、", pending));
            if (remaining > pending.size()) {
                sb.append(" 等").append(remaining).append(" 个");
            }
            sb.append("\n");
        }
        List<String> sticking = new ArrayList<>();
        for (Map<String, Object> record : records) {
            if (Boolean.TRUE.equals(record.get("sticking")) && sticking.size() < 8) {
                sticking.add(String.valueOf(record.get("spelling")));
            }
        }
        if (!sticking.isEmpty()) {
            sb.append("顽固单词（反复忘记）：").append(String.join("、", sticking)).append("\n");
        }
        if (finished == 0 && total > 0) {
            sb.append("今天还没开始背。\n");
        }
        sb.append("请用自然语言把进度讲给用户（可以顺手鼓励一句），数字照抄，不要自己加戏。");
        return sb.toString();
    }

    // ---------- Token 管理 ----------

    public String effectiveToken() {
        Optional<MaimemoSetting> stored = settings.findById(KEY_TOKEN);
        String value = stored.map(MaimemoSetting::getValue).orElse("");
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        return envToken;
    }

    public String tokenSource() {
        String stored = settings.findById(KEY_TOKEN).map(MaimemoSetting::getValue).orElse("");
        if (stored != null && !stored.isBlank()) {
            return "面板保存";
        }
        return envToken.isBlank() ? "未配置" : "服务器环境变量";
    }

    public String maskedToken() {
        String token = effectiveToken();
        if (token.isBlank()) {
            return "";
        }
        if (token.length() <= 12) {
            return token.substring(0, 3) + "…";
        }
        return token.substring(0, 6) + "…" + token.substring(token.length() - 4);
    }

    public String tokenUpdatedAt() {
        return settings.findById(KEY_TOKEN)
                .map(MaimemoSetting::getUpdatedAt)
                .map(value -> value.format(UPDATED_AT))
                .orElse("");
    }

    /** 保存 Token（空字符串＝清除面板里的值，回落到环境变量），并立即校验一次 */
    public Map<String, Object> saveToken(String token) {
        String value = token == null ? "" : token.trim();
        if (value.isEmpty()) {
            settings.deleteById(KEY_TOKEN);
            invalidate();
            Map<String, Object> result = new LinkedHashMap<>(refresh());
            result.put("saved", true);
            result.put("message", envToken.isBlank() ? "已清除面板里的 Token" : "已清除面板里的 Token，改用服务器环境变量");
            return result;
        }
        MaimemoSetting setting = settings.findById(KEY_TOKEN).orElseGet(MaimemoSetting::new);
        setting.setKey(KEY_TOKEN);
        setting.setValue(value);
        setting.setUpdatedAt(LocalDateTime.now(zone));
        settings.save(setting);
        invalidate();
        return refresh();
    }

    // ---------- 每日推送设置 ----------

    public Map<String, Object> pushState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("enabled", pushEnabled());
        state.put("time", pushTime().toString());
        state.put("lastPushDate", settings.findById(KEY_LAST_PUSH_DATE).map(MaimemoSetting::getValue).orElse(""));
        state.put("ready", enabled && !effectiveToken().isBlank());
        return state;
    }

    public boolean pushEnabled() {
        return settings.findById(KEY_PUSH_ENABLED)
                .map(MaimemoSetting::getValue)
                .map(value -> Boolean.parseBoolean(value.trim()))
                .orElse(defaultPushEnabled);
    }

    public LocalTime pushTime() {
        return settings.findById(KEY_PUSH_TIME)
                .map(MaimemoSetting::getValue)
                .map(value -> parseTime(value, defaultPushTime))
                .orElse(defaultPushTime);
    }

    public String lastPushDate() {
        return settings.findById(KEY_LAST_PUSH_DATE).map(MaimemoSetting::getValue).orElse("");
    }

    public void markPushed(LocalDate date) {
        MaimemoSetting setting = settings.findById(KEY_LAST_PUSH_DATE).orElseGet(MaimemoSetting::new);
        setting.setKey(KEY_LAST_PUSH_DATE);
        setting.setValue(date.toString());
        setting.setUpdatedAt(LocalDateTime.now(zone));
        settings.save(setting);
    }

    public void savePush(Boolean enabledValue, String timeValue) {
        if (enabledValue != null) {
            saveSetting(KEY_PUSH_ENABLED, String.valueOf(enabledValue));
        }
        if (timeValue != null && !timeValue.isBlank()) {
            LocalTime parsed = parseTime(timeValue, null);
            if (parsed == null) {
                throw new IllegalArgumentException("推送时间格式应为 HH:mm，例如 21:30");
            }
            saveSetting(KEY_PUSH_TIME, parsed.toString());
        }
    }

    private void saveSetting(String key, String value) {
        MaimemoSetting setting = settings.findById(key).orElseGet(MaimemoSetting::new);
        setting.setKey(key);
        setting.setValue(value);
        setting.setUpdatedAt(LocalDateTime.now(zone));
        settings.save(setting);
    }

    // ---------- 内部 ----------

    private Map<String, Object> store(Map<String, Object> value) {
        cache = value;
        cachedAtMillis = System.currentTimeMillis();
        return value;
    }

    private Map<String, Object> progressMap(MaimemoClient.Progress progress, List<MaimemoClient.TodayItem> items) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("finished", progress.finished());
        map.put("total", progress.total());
        map.put("remaining", progress.remaining());
        map.put("percent", progress.percent());
        map.put("studyTimeMinutes", Math.max(0, progress.studyTimeSeconds()) / 60);
        int newCount = 0;
        for (MaimemoClient.TodayItem item : items) {
            if (item.isNew()) {
                newCount++;
            }
        }
        map.put("newCount", newCount);
        map.put("reviewCount", Math.max(0, items.size() - newCount));
        return map;
    }

    private List<Map<String, Object>> itemList(List<MaimemoClient.TodayItem> items) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (MaimemoClient.TodayItem item : items) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("spelling", item.spelling());
            map.put("order", item.order());
            map.put("isNew", item.isNew());
            map.put("finished", item.isFinished());
            map.put("firstResponse", responseText(item.firstResponse(), item.isFinished()));
            list.add(map);
        }
        return list;
    }

    private List<Map<String, Object>> recordList(List<MaimemoClient.StudyRecord> records) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (MaimemoClient.StudyRecord record : records) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("spelling", record.spelling());
            map.put("nextStudyDate", record.nextStudyDate());
            map.put("lastResponse", responseText(record.lastResponse(), true));
            map.put("studyCount", record.studyCount());
            map.put("sticking", record.sticking());
            list.add(map);
        }
        return list;
    }

    /** 把接口里的英文枚举翻成中文，前端与模型都直接用 */
    private String responseText(String raw, boolean answered) {
        String value = raw == null ? "" : raw.trim().toUpperCase();
        return switch (value) {
            case "FAMILIAR" -> "认识";
            case "WELL_FAMILIAR" -> "熟知";
            case "VAGUE" -> "模糊";
            case "FORGET" -> "忘记";
            case "CANCEL_WELL_FAMILIAR" -> "取消熟知";
            default -> answered ? "" : "未学";
        };
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static LocalTime parseTime(String value, LocalTime fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalTime.parse(value.trim());
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static ZoneId parseZone(String value) {
        try {
            return ZoneId.of(value);
        } catch (RuntimeException ignored) {
            return ZoneId.of("Asia/Shanghai");
        }
    }
}
