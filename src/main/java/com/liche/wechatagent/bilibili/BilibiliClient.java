package com.liche.wechatagent.bilibili;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B 站只读客户端：解析视频号 → 取元信息 → 取字幕。
 *
 * <p><b>为什么有这个东西</b>（2026-10-10）：用户在 QQ 里转发 B站视频，卡片只给标题和封面
 * （平台把跳转链接留在 {@code ark_data.fields.jump_url} 里，见 {@code QqArkCard}）。
 * 要真的"看懂视频"，只能顺着链接去取**字幕**——B站的 AI 字幕/UP 主字幕覆盖了绝大多数视频。
 *
 * <p><b>取字幕需要登录态</b>：{@code sessdata} 为空时通常拿不到字幕（尤其是 AI 字幕），
 * 这时会如实返回"没有字幕"，不假装看过。凭据只从服务器 <code>.env</code> 的 {@code BILI_SESSDATA} 来。
 *
 * <p>链路与降级照抄用户本地那份跑通的 {@code 哔哩哔哩视频总结/src/bilibili.js}：
 * {@code /x/web-interface/view} 拿 cid → {@code /x/player/wbi/v2} 拿字幕轨
 * （失败退回无签名的 {@code /x/player/v2} 再试一次）→ 下载 {@code subtitle_url} 的 JSON。
 */
@Component
public class BilibiliClient {

    /** 照抄浏览器 UA：不带它 B站接口容易返回空数据 */
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String API = "https://api.bilibili.com";
    private static final String HOME = "https://www.bilibili.com/";
    private static final Pattern BV_PATTERN = Pattern.compile("BV[0-9A-Za-z]{10}");
    private static final Pattern AV_PATTERN = Pattern.compile("\\bav(\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAGE_PATTERN = Pattern.compile("[?&]p=(\\d+)");

    private final OkHttpClient http;
    private final OkHttpClient redirectProbe;
    private final ObjectMapper objectMapper;
    private final String cookie;
    private final boolean configured;

    public BilibiliClient(ObjectMapper objectMapper,
                          @Value("${bilibili.sessdata:}") String sessdata,
                          @Value("${bilibili.timeout-seconds:20}") int timeoutSeconds) {
        this.objectMapper = objectMapper;
        this.cookie = buildCookieHeader(sessdata);
        this.configured = !cookie.isBlank();
        int timeout = Math.max(3, Math.min(120, timeoutSeconds));
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(Math.min(timeout, 8)))
                .readTimeout(Duration.ofSeconds(timeout))
                .followRedirects(true)
                .build();
        // 短链只看一跳，自己校验跳到哪去，不盲目跟随
        this.redirectProbe = http.newBuilder().followRedirects(false).build();
    }

    public boolean configured() {
        return configured;
    }

    /** 视频标识：BV 号优先；只有 av 号时用 aid。page 是分 P（1 起）。 */
    public record VideoRef(String bvid, Long aid, Integer page) {
    }

    public record Page(int index, long cid, String title, int durationSeconds) {
    }

    public record VideoInfo(String bvid, long aid, String title, String desc, String owner,
                            int durationSeconds, List<Page> pages) {
    }

    public record Transcript(boolean available, String reason, String language, boolean aiGenerated, String text) {
    }

    /**
     * 从任意输入里认视频：完整链接 / BV 号 / av 号 / b23.tv 短链 / 带 {@code ?p=3} 的分 P。
     *
     * <p>认不出来属于**确定性失败**（同一个输入再试多少次都一样），所以抛业务异常让工具直接回话，不走重试。
     */
    public VideoRef parse(String input) throws IOException {
        String text = input == null ? "" : input.trim();
        if (text.isBlank()) {
            throw new BilibiliException("没看到链接或 BV 号。");
        }
        Integer page = intOrNull(text, PAGE_PATTERN);
        Matcher bv = BV_PATTERN.matcher(text);
        if (bv.find()) {
            return new VideoRef(bv.group(), null, page);
        }
        Matcher av = AV_PATTERN.matcher(text);
        if (av.find()) {
            return new VideoRef(null, Long.parseLong(av.group(1)), page);
        }
        if (text.regionMatches(true, 0, "http", 0, 4)) {
            String resolved = followOneHop(text);
            Matcher shortBv = BV_PATTERN.matcher(resolved);
            if (shortBv.find()) {
                return new VideoRef(shortBv.group(), null, page != null ? page : intOrNull(resolved, PAGE_PATTERN));
            }
            Matcher shortAv = AV_PATTERN.matcher(resolved);
            if (shortAv.find()) {
                return new VideoRef(null, Long.parseLong(shortAv.group(1)), page);
            }
            throw new BilibiliException("这个链接跳转后没找到视频号，换个链接试试。");
        }
        throw new BilibiliException("认不出这是哪个视频，把 B 站链接或 BV 号发我。");
    }

    public VideoInfo videoInfo(VideoRef ref) throws IOException {
        String query = ref.bvid() != null ? "bvid=" + ref.bvid() : "aid=" + ref.aid();
        JsonNode root = get(API + "/x/web-interface/view?" + query, null);
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            throw new BilibiliException(viewError(code, root.path("message").asText("")));
        }
        JsonNode data = root.path("data");
        List<Page> pages = new ArrayList<>();
        for (JsonNode item : data.path("pages")) {
            pages.add(new Page(item.path("page").asInt(), item.path("cid").asLong(),
                    item.path("part").asText(""), item.path("duration").asInt()));
        }
        return new VideoInfo(data.path("bvid").asText(ref.bvid() == null ? "" : ref.bvid()),
                data.path("aid").asLong(0L), data.path("title").asText(""), data.path("desc").asText(""),
                data.path("owner").path("name").asText(""), data.path("duration").asInt(0), List.copyOf(pages));
    }

    /**
     * 用**标题**去 B 站搜一个视频，返回 BV 号；**只有标题逐字一致才认**，否则返回 {@code null}。
     *
     * <p><b>为什么要它</b>（2026-10-10）：用户转发的 B站卡片**不带链接**（实测 {@code ark_data.fields}
     * 只有 {@code [preview, source, source_logo, title]}），所以拿不到 BV。但卡片给了**完整标题**，
     * 而 B站自己的搜索很准——实测三条真实的转发标题**全部在第一位就搜到逐字一致的结果**。
     *
     * <p><b>为什么要求逐字一致</b>：拿错视频比没拿到更糟——模型会对着一个不相干的视频侃侃而谈，
     * 而且从回复里看不出任何异常。宁可返回 null，让上游如实说"搜不到，把链接发我"。
     *
     * <p>这个接口**不需要 wbi 签名**（2026-10-10 实测带 Cookie 直接 {@code code:0}）；
     * 返回的 {@code title} 里带 {@code <em>} 高亮标签，比较前要去掉。
     */
    public String searchByTitle(String title) throws IOException {
        String keyword = title == null ? "" : title.trim();
        if (keyword.isBlank()) {
            return null;
        }
        String encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8).replace("+", "%20");
        JsonNode root = get(API + "/x/web-interface/search/type?search_type=video&keyword=" + encoded, HOME);
        if (root.path("code").asInt(-1) != 0) {
            return null;
        }
        String wanted = normalizeTitle(keyword);
        for (JsonNode item : root.path("data").path("result")) {
            if (normalizeTitle(deTag(item.path("title").asText(""))).equals(wanted)) {
                String bvid = item.path("bvid").asText("");
                if (!bvid.isBlank()) {
                    return bvid;
                }
            }
        }
        return null;
    }

    /** 去掉搜索结果标题里的 {@code <em class="keyword">} 高亮标签，并还原常见实体。 */
    private static String deTag(String title) {
        String text = title == null ? "" : title.replaceAll("<[^>]+>", "");
        return text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
    }

    /** 比标题时忽略空白与大小写——推送里偶尔多个空格，那不该算"不一致"。 */
    private static String normalizeTitle(String title) {
        return title == null ? "" : title.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /** 取字幕正文。拿不到就返回 {@code available=false} 并说明原因——**上层要如实转告，不许编**。 */
    public Transcript transcript(String bvid, long cid, int maxChars) throws IOException {
        JsonNode tracks = subtitleTracks(bvid, cid);
        if (tracks == null) {
            return new Transcript(false, "这个视频的字幕信息拿不到（接口没返回）。", "", false, "");
        }
        List<JsonNode> usable = new ArrayList<>();
        for (JsonNode track : tracks) {
            if (track != null && !track.path("subtitle_url").asText("").isBlank()) {
                usable.add(track);
            }
        }
        if (usable.isEmpty()) {
            return new Transcript(false, configured
                    ? "这个视频没有可用字幕（UP 主没传、也没有 B 站 AI 字幕）。"
                    : "这个视频没读到字幕（也可能是没登录 B 站账号，AI 字幕需要登录态）。",
                    "", false, "");
        }
        JsonNode picked = pick(usable);
        List<String> lines = downloadSubtitle(picked.path("subtitle_url").asText(""));
        if (lines.isEmpty()) {
            return new Transcript(false, "字幕轨在，但内容是空的。", "", false, "");
        }
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            if (text.length() + line.length() + 1 > maxChars) {
                text.append("\n…（字幕太长，后面省略）");
                break;
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(line);
        }
        return new Transcript(true, "", picked.path("lan").asText(""), picked.path("ai_type").asInt(0) == 1,
                text.toString());
    }

    // ------------------------------------------------------------------ 内部

    private JsonNode subtitleTracks(String bvid, long cid) throws IOException {
        String referer = "https://www.bilibili.com/video/" + bvid;
        JsonNode root = get(API + "/x/player/wbi/v2?bvid=" + bvid + "&cid=" + cid, referer);
        if (root.path("code").asInt(-1) != 0) {
            // 参考实现（用户的 bilibili.js）在这里退回旧版无签名地址再试一次
            JsonNode legacy = get(API + "/x/player/v2?bvid=" + bvid + "&cid=" + cid, referer);
            return legacy.path("code").asInt(-1) == 0 ? findSubtitles(legacy.path("data")) : null;
        }
        return findSubtitles(root.path("data"));
    }

    /** 字幕轨在返回体里的位置有好几种写法（新版/旧版/不同视频），逐个试。 */
    private static JsonNode findSubtitles(JsonNode data) {
        if (data == null || !data.isObject()) {
            return null;
        }
        for (JsonNode candidate : List.of(data.path("subtitle").path("subtitles"),
                data.path("subtitle").path("list"), data.path("subtitles"), data.path("subtitle"))) {
            if (candidate.isArray()) {
                return candidate;
            }
        }
        return null;
    }

    /** 中文字幕优先，其次 AI 字幕，最后随便挑一条。 */
    private static JsonNode pick(List<JsonNode> tracks) {
        for (JsonNode track : tracks) {
            String lan = track.path("lan").asText("").toLowerCase(Locale.ROOT);
            if (lan.contains("zh-cn") || lan.contains("zh-hans") || lan.contains("ai-zh")) {
                return track;
            }
        }
        for (JsonNode track : tracks) {
            if (track.path("lan").asText("").toLowerCase(Locale.ROOT).startsWith("zh")) {
                return track;
            }
        }
        for (JsonNode track : tracks) {
            if (track.path("ai_type").asInt(0) == 0) {
                return track;
            }
        }
        return tracks.get(0);
    }

    private List<String> downloadSubtitle(String url) throws IOException {
        String target = url.startsWith("//") ? "https:" + url
                : url.startsWith("http://") ? "https://" + url.substring("http://".length()) : url;
        JsonNode root = get(target, HOME);
        List<String> lines = new ArrayList<>();
        for (JsonNode line : root.path("body")) {
            String content = line.path("content").asText("").trim();
            if (!content.isBlank()) {
                lines.add("[" + timestamp(line.path("from").asDouble(0)) + "] " + content);
            }
        }
        return lines;
    }

    private static String timestamp(double seconds) {
        int total = (int) Math.max(0, Math.floor(seconds));
        int hours = total / 3600;
        int minutes = (total % 3600) / 60;
        int secs = total % 60;
        return hours > 0 ? String.format("%d:%02d:%02d", hours, minutes, secs)
                : String.format("%02d:%02d", minutes, secs);
    }

    /** 只看一跳，并且要求跳转目标仍然是 B 站域名——短链是用户给的，不能盲目跟随。 */
    private String followOneHop(String url) throws IOException {
        Request request = new Request.Builder().url(url).header("User-Agent", USER_AGENT)
                .header("Referer", HOME).get().build();
        try (Response response = redirectProbe.newCall(request).execute()) {
            String location = response.header("Location");
            if (location == null || location.isBlank()) {
                return url;
            }
            String lowered = location.toLowerCase(Locale.ROOT);
            if (!lowered.contains("bilibili.com") && !lowered.contains("b23.tv")) {
                throw new BilibiliException("这个短链跳到的不是 B 站，我不跟。");
            }
            return location;
        }
    }

    private JsonNode get(String url, String referer) throws IOException {
        Request.Builder builder = new Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", referer == null ? HOME : referer)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .get();
        if (!cookie.isBlank()) {
            builder.header("Cookie", cookie);
        }
        try (Response response = http.newCall(builder.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("B 站接口 HTTP " + response.code());
            }
            return objectMapper.readTree(response.body().string());
        }
    }

    private String viewError(int code, String message) {
        if (code == -404) {
            return "这个视频不存在或已被删除。";
        }
        if (code == -101 || code == -400) {
            return "登录态失效了（B 站返回 " + code + "）。";
        }
        return "取视频信息失败：" + (message == null || message.isBlank() ? "错误码 " + code : message);
    }

    /**
     * 把配置里的登录信息整理成 Cookie 头。照抄参考实现：扫码登录存的是整条 Cookie 串，
     * 手工填的通常只有 SESSDATA 的值；两者都要认。缺 buvid3 时补一个随机值。
     */
    static String buildCookieHeader(String input) {
        String raw = input == null ? "" : input.trim();
        if (raw.isBlank()) {
            return "";
        }
        String header;
        if (raw.contains("=")) {
            StringBuilder joined = new StringBuilder();
            for (String part : raw.split("[;\\r\\n]+")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (joined.length() > 0) {
                    joined.append("; ");
                }
                joined.append(trimmed);
            }
            header = joined.toString();
        } else {
            header = "SESSDATA=" + raw;
        }
        if (!header.toLowerCase(Locale.ROOT).contains("buvid3=")) {
            header += "; buvid3=" + randomBuvid();
        }
        return header;
    }

    private static String randomBuvid() {
        String hex = "0123456789ABCDEF";
        StringBuilder value = new StringBuilder(32);
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < 32; i++) {
            value.append(hex.charAt(random.nextInt(hex.length())));
        }
        return value + "infoc";
    }

    private static Integer intOrNull(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            int value = Integer.parseInt(matcher.group(1));
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 确定性失败：链接不认、视频不存在、没字幕——**不要交给工具框架重试**（坑 67）。 */
    public static class BilibiliException extends RuntimeException {
        public BilibiliException(String message) {
            super(message);
        }
    }
}
