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
    private final MaimemoOidcService oidcService;
    private final com.liche.wechatagent.config.AlertProperties alertProperties;
    private final String configuredOwnerUserId;
    private final boolean enabled;
    private final String envToken;
    private final boolean defaultPushEnabled;
    private final LocalTime defaultPushTime;
    private final int itemLimit;
    private final int recordFetchLimit;
    private final long stickyCacheMillis;
    private final long cacheMillis;
    private final ZoneId zone;

    private volatile Map<String, Object> cache;
    private volatile long cachedAtMillis;
    /** 顽固单词单独的缓存（拉的是全量学习记录，比快照缓存更久） */
    private volatile Map<String, Object> stickingCache;
    private volatile long stickingCachedAtMillis;

    public MaimemoService(MaimemoClient client,
                          MaimemoSettingRepository settings,
                          MaimemoOidcService oidcService,
                          com.liche.wechatagent.config.AlertProperties alertProperties,
                          @Value("${maimemo.owner-user-id:}") String ownerUserId,
                          @Value("${maimemo.enabled:true}") boolean enabled,
                          @Value("${maimemo.api-token:}") String envToken,
                          @Value("${maimemo.daily-push-enabled:true}") boolean defaultPushEnabled,
                          @Value("${maimemo.daily-push-time:21:30}") String defaultPushTime,
                          @Value("${maimemo.item-limit:30}") int itemLimit,
                          @Value("${maimemo.record-fetch-limit:1000}") int recordFetchLimit,
                          @Value("${maimemo.sticking-cache-seconds:600}") int stickingCacheSeconds,
                          @Value("${maimemo.cache-seconds:30}") int cacheSeconds,
                          @Value("${app.time-zone:Asia/Shanghai}") String timeZone) {
        this.client = client;
        this.settings = settings;
        this.oidcService = oidcService;
        this.alertProperties = alertProperties;
        this.configuredOwnerUserId = ownerUserId == null ? "" : ownerUserId.trim();
        this.enabled = enabled;
        this.envToken = envToken == null ? "" : envToken.trim();
        this.defaultPushEnabled = defaultPushEnabled;
        this.defaultPushTime = parseTime(defaultPushTime, LocalTime.of(21, 30));
        this.itemLimit = Math.max(5, Math.min(200, itemLimit));
        this.recordFetchLimit = Math.max(50, Math.min(1000, recordFetchLimit));
        this.stickyCacheMillis = Math.max(30, Math.min(3600, stickingCacheSeconds)) * 1000L;
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
        result.put("tokenHint", oidcService.authorized() ? "（OIDC 授权，自动续期，不用手工维护）" : maskedToken());
        result.put("tokenUpdatedAt", tokenUpdatedAt());
        result.put("push", pushState());
        result.put("oidc", oidcService.status());
        result.put("ownerUserId", ownerUserId());

        if (!enabled) {
            result.put("status", STATUS_DISABLED);
            result.put("message", "墨墨接入已关闭（maimemo.enabled=false）");
            return store(result);
        }

        // OIDC 授权优先（长期、自动续期）；刷新失败时退回到面板 Token / 环境变量，并把原因带给用户
        String oidcError = "";
        String token = "";
        boolean oidcUsed = false;
        if (oidcService.authorized()) {
            try {
                token = oidcService.accessTokenOrNull();
                oidcUsed = token != null && !token.isBlank();
            } catch (MaimemoClient.MaimemoAuthException exception) {
                oidcError = exception.getMessage();
                token = "";
            }
        }
        if (token == null || token.isBlank()) {
            token = effectiveToken();
            if (!oidcError.isEmpty()) {
                result.put("tokenSource", tokenSource() + "（OIDC 刷新失败，已回退）");
            }
        }
        if (token.isBlank()) {
            result.put("status", STATUS_NOT_CONFIGURED);
            result.put("message", oidcError.isEmpty()
                    ? "还没配置墨墨 Token：可以在墨墨 App 生成后粘贴到这里，或者配置 OIDC 授权长期使用。"
                    : oidcError);
            return store(result);
        }
        try {
            return store(load(result, token, oidcError));
        } catch (MaimemoClient.MaimemoAuthException exception) {
            // OIDC 的 access token 可能被服务端提前作废，强制刷新一次再试
            if (oidcUsed) {
                try {
                    oidcService.forceRefresh();
                    String retried = oidcService.accessTokenOrNull();
                    if (retried != null && !retried.isBlank()) {
                        return store(load(result, retried, ""));
                    }
                } catch (RuntimeException ignored) {
                    // 刷新也失败：按下面的授权失效处理
                }
            }
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

    /** 取一次上游数据并写进结果快照 */
    private Map<String, Object> load(Map<String, Object> result, String token, String oidcError) {
        MaimemoClient.Progress progress = client.progress(token);
        // 多取一些：新学/复习的拆分要用完整列表算，面板只展示前面 itemLimit 条
        int fetchLimit = (int) Math.min(1000L, Math.max(itemLimit, progress.total()));
        List<MaimemoClient.TodayItem> items = client.todayItems(token, fetchLimit);
        List<MaimemoClient.StudyRecord> records = client.records(token, itemLimit);
        result.put("status", STATUS_OK);
        result.put("message", oidcError.isEmpty()
                ? "已连接墨墨开放 API"
                : "已连接（OIDC 刷新失败，当前用的是面板 Token：" + oidcError + "）");
        result.put("progress", progressMap(progress, items));
        result.put("todayItems", itemList(items.size() > itemLimit ? items.subList(0, itemLimit) : items));
        result.put("records", recordList(records));
        result.put("sticking", stickingInfo(token));
        return result;
    }

    /**
     * 顽固单词（反复忘记、被墨墨打上 STICKING 的词）。
     *
     * <p>接口只在**完整**学习记录里带这个标签（实测 833 条记录里有 35 个 STICKING），
     * 只拉前面几十条基本永远看不到，所以这里单独拉一次大列表并按更长的缓存时间（默认 10 分钟）存着——
     * 顽固词变化很慢，不必每次刷新都拉全量。
     */
    private Map<String, Object> stickingInfo(String token) {
        Map<String, Object> cached = stickingCache;
        if (cached != null && System.currentTimeMillis() - stickingCachedAtMillis < stickyCacheMillis) {
            return cached;
        }
        Map<String, Object> info = new LinkedHashMap<>();
        try {
            List<MaimemoClient.StudyRecord> all = client.records(token, recordFetchLimit);
            List<Map<String, Object>> words = new ArrayList<>();
            for (MaimemoClient.StudyRecord record : all) {
                if (record.sticking() && words.size() < 100) {
                    Map<String, Object> word = new LinkedHashMap<>();
                    word.put("spelling", record.spelling());
                    word.put("lastResponse", responseText(record.lastResponse(), true));
                    word.put("studyCount", record.studyCount());
                    words.add(word);
                }
            }
            info.put("count", words.size());
            info.put("scanned", all.size());
            info.put("words", words);
        } catch (RuntimeException exception) {
            log.warn("读取顽固单词失败: {}", exception.getMessage());
            info.put("count", 0);
            info.put("scanned", 0);
            info.put("words", List.of());
            info.put("error", exception.getMessage());
        }
        stickingCache = info;
        stickingCachedAtMillis = System.currentTimeMillis();
        return info;
    }

    /** Token 失效后清缓存，让下一次读取立刻重试 */
    public void invalidate() {
        cachedAtMillis = 0L;
    }

    /**
     * 墨墨账号的归属用户（openid）。
     *
     * <p>墨墨是**单账号**接口：Token 属于某一个人的墨墨账号，所以这套数据只属于这一个人。
     * 优先用 {@code maimemo.owner-user-id}，没配就回落到运维告警里配置的本人 openid；
     * 两者都为空时返回空字符串（表示没有绑定，任何人都可用——只适合单用户部署）。
     */
    public String ownerUserId() {
        if (!configuredOwnerUserId.isBlank()) {
            return configuredOwnerUserId;
        }
        String openid = alertProperties == null ? "" : alertProperties.getQqOpenid();
        return openid == null ? "" : openid.trim();
    }

    /** 这个用户是否有权查看墨墨数据 */
    public boolean isMaimemoOwner(String userId) {
        String owner = ownerUserId();
        return owner.isBlank() || owner.equals(userId);
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
        Map<String, Object> stickingInfo = (Map<String, Object>) snapshot.getOrDefault("sticking", Map.of());

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
        if (progress.get("newCount") != null) {
            sb.append("其中新学 ").append(number(progress.get("newCount")))
                    .append(" 个、复习 ").append(number(progress.get("reviewCount"))).append(" 个；");
        }
        sb.append("学习时长约 ").append(number(progress.get("studyTimeMinutes"))).append(" 分钟\n");

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
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stickyWords = (List<Map<String, Object>>) stickingInfo.getOrDefault("words", List.of());
        for (Map<String, Object> word : stickyWords) {
            if (sticking.size() < 15) {
                sticking.add(String.valueOf(word.get("spelling")));
            }
        }
        if (!sticking.isEmpty()) {
            sb.append("顽固单词（墨墨标记为反复忘记，共 ").append(number(stickingInfo.get("count")))
                    .append(" 个）：").append(String.join("、", sticking));
            if (number(stickingInfo.get("count")) > sticking.size()) {
                sb.append(" 等");
            }
            sb.append("\n");
        } else {
            sb.append("顽固单词：这次没查到（已扫描 ").append(number(stickingInfo.get("scanned")))
                    .append(" 条学习记录）——如实说没查到，不要编单词。\n");
        }
        if (finished == 0 && total > 0) {
            sb.append("今天还没开始背。\n");
        }
        sb.append("讲给用户时：进度、词表、顽固标记这些**墨墨的数据必须照抄**，不要自己加戏；"
                + "但**单词的中文释义可以你自己给**（墨墨开放 API 不提供官方释义），给释义时按常见义项写、不确定就只说词性和大意，"
                + "不要声称那是墨墨官方释义。");
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
        if (oidcService.authorized()) {
            return "OIDC 授权";
        }
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
        // 接口只返回今日"总数"，新学/复习的拆分只能靠今日单词列表算——
        // 列表没取全（total 大于本次拉取条数）时不能拿列表长度当复习数，宁可留空。
        boolean complete = progress.total() > 0 && items.size() >= progress.total();
        map.put("newCount", complete ? newCount : null);
        map.put("reviewCount", complete ? Math.max(0, items.size() - newCount) : null);
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
            map.put("nextStudyDate", localDate(record.nextStudyDate()));
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

    /**
     * 接口返回的是 UTC 的 ISO 时间（如 2026-09-22T16:00:00.000Z，其实就是本地 09-23 零点），
     * 直接展示会让人以为差一天，所以统一换算成本地日期。
     */
    private String localDate(String iso) {
        if (iso == null || iso.isBlank()) {
            return "";
        }
        try {
            return java.time.OffsetDateTime.parse(iso)
                    .atZoneSameInstant(zone)
                    .toLocalDate()
                    .toString();
        } catch (RuntimeException exception) {
            return iso;
        }
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
