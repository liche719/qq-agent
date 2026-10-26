package com.liche.qqagent.search;

import com.liche.qqagent.tool.ToolStatusService;
import com.liche.qqagent.network.PublicUrlValidator;
import com.liche.qqagent.tool.ToolBusinessResult;
import com.liche.qqagent.tool.ToolExecutionPolicy;
import com.liche.qqagent.tool.ToolExecutionClass;
import dev.langchain4j.agent.tool.Tool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;

@Component
public class WebPageTool implements com.liche.qqagent.tool.AgentToolProvider {

    private static final int DEFAULT_MAX_REDIRECTS = 3;
    private static final long DEFAULT_CONNECT_TIMEOUT_SECONDS = 8;
    private static final long DEFAULT_READ_TIMEOUT_SECONDS = 20;
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (compatible; WechatAgent/1.0)";

    private final OkHttpClient client;
    private final int maxResponseBytes;
    private final int maxTextChars;
    private final int maxRedirects;
    private final String userAgent;
    private final PublicUrlValidator urlValidator;
    /**
     * 本地抓不到正文时的补救（2026-10-20 加）：DS 的 `tools/fetch` 是渲染后取正文，
     * 能救回**靠 JS 渲染**的站点。按次收费，所以只在"确实没抓到东西"时才调，并共用搜索配额。
     */
    private DeepSeekSearchClient deepSeekSearchClient;
    private DeepSeekSearchQuota quota;
    /**
     * 正文少于这么多字就认为"没抓到可读内容"。
     *
     * <p>2026-10-20 定这个数的原则是：**它只兜"明显是空壳"这一种情况**（导航栏 + 版权声明）。
     * "要不要完整正文"由模型自己用 `fullText` 参数决定——程序不该替它判断该不该花钱。
     * 所以这里是 200 而不是 1500：宁可让它自己判断，也不要把"能不能读"赌在一个字数上。
     */
    private int deepSeekFetchMinChars = 200;

    /**
     * 最近失败的网址（2026-10-21 加）。
     *
     * <p>为什么要记：实测它有"死磕"行为——同一个 GitHub 页面失败后连着调 4 次、一分钟 7 次，
     * 每次都等一轮超时。等待时间全是白费的，而且它并不是想换方法，是**已经不记得刚试过**。
     *
     * <p>记 5 分钟就够：网络抖动恢复得比这快，太长的记忆反而会挡住"刚恢复"的站。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> recentFailures =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long FAILURE_MEMORY_MILLIS = 5 * 60 * 1000L;

    /**
     * 确定性不可读：链接非法、HTTP 4xx、不是网页文本、跳转异常……同一个链接再试多少次都一样。
     *
     * <p>**为什么要单独分一类**（2026-10-09 实测修）：原来这些情况一律 `throw IllegalStateException`，
     * 而本工具的策略是 {@code SLOW_EXTERNAL}（可重试），于是框架**必然重试一次**——404 等一轮还是 404，
     * 用户白等一个往返，失败记录里也留下误导性的「已自动重试 1 次」。实测 24 次 `readWebPage` 有 8 次失败
     * （33%），全是这一类。瞬时故障（超时 / 连接失败 / DNS / 5xx）仍然抛异常，继续享受重试。
     */
    private static final class UnreadablePageException extends RuntimeException {
        UnreadablePageException(String message) {
            super(message);
        }
    }

    @Tool(value = "读一个公开网页的正文（用户给了 URL，或要看搜索结果里的具体页面）。**只读这一页，不搜索**；要搜索用 searchWeb。")
    @ToolExecutionPolicy(ToolExecutionClass.SLOW_EXTERNAL)
    public ToolBusinessResult readWebPage(String url, Boolean fullText) {
        // 5 分钟内刚失败过的网址：直接告诉他别再试同一个，省一轮超时也省一次思考
        Long failedAt = url == null ? null : recentFailures.get(url.trim());
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILURE_MEMORY_MILLIS) {
            return ToolBusinessResult.failure("这个链接刚才试过了，**打不开**（几分钟内同样的结果）。"
                    + "别再重试同一个地址——换个来源：用 searchWeb 找同一内容的别的页面，"
                    + "或者找它的镜像 / 缓存。");
        }
        status("我正在打开这个网页…");
        // 模型明确要"完整正文"：直接走 DeepSeek 渲染后取全文，不去猜字数
        if (Boolean.TRUE.equals(fullText)) {
            String full = fetchFromDeepSeek(url);
            if (!full.isBlank()) {
                return ToolBusinessResult.success(full);
            }
            // 拿不到就退回本地抓取，不能让"想要全文"变成"什么都没读到"
        }
        try {
            String text = openAndExtract(url);
            // 读成功了就把失败记录抹掉：否则这个网址在 5 分钟内即使恢复也会被缓存挡着
            if (url != null) {
                recentFailures.remove(url.trim());
            }
            return ToolBusinessResult.success(fillIfTooShort(url, text));
        } catch (UnreadablePageException exception) {
            rememberFailure(url);
            // 本地读不了（常见于纯 JS 渲染 / 需要执行脚本的站）：让 DS 试着抓一次
            String rescued = fetchFromDeepSeek(url);
            if (!rescued.isBlank()) {
                return ToolBusinessResult.success(rescued);
            }
            return ToolBusinessResult.failure(exception.getMessage());
        } catch (IllegalArgumentException exception) {
            return ToolBusinessResult.failure("这个链接不安全或格式不正确，无法访问");
        } catch (IOException exception) {
            if (hasCauseMessage(exception, "response too large")) {
                return ToolBusinessResult.failure("网页内容超过安全读取上限（"
                        + readableSize(maxResponseBytes) + "），无法读取");
            }
            // 超时 / 连接失败 / DNS。GitHub 的 raw 地址在国内时通时断，
            // 先换 jsdelivr 镜像试一次，别让"读自己的代码"变成运气问题。
            String mirrored = tryMirror(url);
            if (mirrored != null && !mirrored.isBlank()) {
                return ToolBusinessResult.success(fillIfTooShort(url, mirrored));
            }
            rememberFailure(url);
            throw new IllegalStateException("网页暂时无法访问，请稍后重试或换一个链接", exception);
        }
    }

    /** 记下这次失败（同一个地址 5 分钟内不再重复尝试） */
    private void rememberFailure(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        if (recentFailures.size() > 200) {
            recentFailures.clear();
        }
        recentFailures.put(url.trim(), System.currentTimeMillis());
    }

    /** 走镜像再试一次（只对 raw.githubusercontent 有意义） */
    private String tryMirror(String rawUrl) {
        String mirror = mirrorForRawGithub(normalizeForRawText(rawUrl));
        if (mirror == null) {
            return null;
        }
        try {
            org.slf4j.LoggerFactory.getLogger(WebPageTool.class).info("GitHub 直连失败，改走镜像：{}", mirror);
            return openAndExtract(mirror);
        } catch (Exception exception) {
            org.slf4j.LoggerFactory.getLogger(WebPageTool.class)
                    .warn("镜像也失败：{} -> {}", mirror, exception.getMessage());
            return null;
        }
    }

    /**
     * 把「代码托管站的网页视图」换成正文本地址（2026-10-20 加）。
     *
     * <p>为什么值得单独做：用户的场景是"去看我 GitHub 仓库里的代码，一层层深入"。
     * `github.com/o/r/blob/ref/path` 返回的是**带整页导航的 HTML**，Jsoup 抓下来又脏又费 token；
     * 而 `raw.githubusercontent.com/o/r/ref/path` 就是**纯文本**，干净、免费、还支持任意文件。
     * 所以这一步在抓取之前做，省掉一次 DS fetch 的钱。
     */
    private String normalizeForRawText(String url) {
        if (url == null) {
            return url;
        }
        String trimmed = url.trim();
        java.util.regex.Matcher github = java.util.regex.Pattern
                .compile("^https?://(?:www\\.)?github\\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+)$")
                .matcher(trimmed);
        if (github.matches()) {
            String owner = github.group(1);
            String repo = github.group(2);
            String ref = github.group(3);
            String path = github.group(4);
            // 2026-10-21：**直接走 jsdelivr**，不先试 raw.githubusercontent。
            // 实测这台机器的 hosts 把 github.com / raw.githubusercontent.com 都指到了
            // 127.0.0.1（GFW 污染），于是 PublicUrlValidator 判定"私有地址"直接拒绝——
            // 拦得没错，但用户想读的是自己的代码。jsdelivr 解析正常（151.101.x.x），
            // 同样的路径换个域名就能拿到，而且是给 GitHub 做镜像的正规 CDN。
            String mirror = "https://cdn.jsdelivr.net/gh/" + owner + "/" + repo + "@" + ref + "/" + path;
            org.slf4j.LoggerFactory.getLogger(WebPageTool.class)
                    .info("代码页转 jsdelivr：{} -> {}", trimmed, mirror);
            return mirror;
        }
        // 已经是 raw 地址的，也换成 jsdelivr（raw 的域名在国内解析不了）
        java.util.regex.Matcher rawMatch = java.util.regex.Pattern
                .compile("^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$")
                .matcher(trimmed);
        if (rawMatch.matches()) {
            String mirror = "https://cdn.jsdelivr.net/gh/" + rawMatch.group(1) + "/" + rawMatch.group(2)
                    + "@" + rawMatch.group(3) + "/" + rawMatch.group(4);
            org.slf4j.LoggerFactory.getLogger(WebPageTool.class)
                    .info("raw 地址转 jsdelivr：{} -> {}", trimmed, mirror);
            return mirror;
        }
        return trimmed;
    }

    /**
     * 给 raw.githubusercontent 找一个国内能通的镜像（2026-10-21）。
     *
     * <p>为什么需要：三天里 readWebPage 失败 8 次，**全是 GitHub** ——
     * 本机（和这台服务器）到大厂 CDN 还行，到 raw.githubusercontent.com 时通时断，
     * 报的是"网页暂时无法访问"（连接超时）。它想读自己的代码，试六次才读到一段。
     *
     * <p>jsdelivr 在国内走的是有备案的 CDN，同样的文件路径换个域名就能拿到，
     * 而且它本来就给 GitHub 仓库做镜像，不是第三方不可信代理。
     *
     * @return 镜像地址；不是 raw 地址就返回 null
     */
    private String mirrorForRawGithub(String url) {
        if (url == null) {
            return null;
        }
        java.util.regex.Matcher raw = java.util.regex.Pattern
                .compile("^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$")
                .matcher(url.trim());
        if (!raw.matches()) {
            return null;
        }
        // jsdelivr 的 GitHub 路径是 /gh/<owner>/<repo>@<ref>/<path>
        return "https://cdn.jsdelivr.net/gh/" + raw.group(1) + "/" + raw.group(2)
                + "@" + raw.group(3) + "/" + raw.group(4);
    }

    private String openAndExtract(String rawUrl) throws IOException {
        URI current = urlValidator.validate(normalizeForRawText(rawUrl));
        for (int redirects = 0; redirects <= maxRedirects; redirects++) {
            Request request = new Request.Builder()
                    .url(current.toString())
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,text/plain,application/json;q=0.9,*/*;q=0.1")
                    .get()
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isRedirect()) {
                    String location = response.header("Location");
                    if (location == null || location.isBlank()) {
                        throw new UnreadablePageException("网页跳转失败：服务端没有提供跳转地址");
                    }
                    current = urlValidator.validate(current.resolve(location).toString());
                    continue;
                }
                if (!response.isSuccessful() || response.body() == null) {
                    int code = response.code();
                    if (code >= 400 && code < 500) {
                        throw new UnreadablePageException("网页访问失败：HTTP " + code);
                    }
                    throw new IllegalStateException("网页访问失败：HTTP " + code);
                }
                String contentType = response.header("Content-Type", "").toLowerCase();
                if (!contentType.startsWith("text/") && !contentType.contains("json")) {
                    throw new UnreadablePageException("这个链接不是可读取的网页文本，当前只支持 HTML、纯文本或 JSON 页面");
                }
                return extractText(current.toString(), contentType, readBody(response));
            }
        }
        throw new UnreadablePageException("网页跳转次数过多，无法继续访问");
    }

    /**
     * 本地抓到的正文太短时，用 DS 再抓一次（2026-10-20）。
     *
     * <p>判断依据故意用**字数**而不是"看起来像不像空壳"：中文网页的正文很少少于这个数，
     * 而导航栏 + 版权声明通常凑不够。误判的代价是白花一次 fetch（约 0.005 元），可接受。
     */
    private String fillIfTooShort(String url, String text) {
        if (text == null || text.length() >= deepSeekFetchMinChars) {
            return text;
        }
        String rescued = fetchFromDeepSeek(url);
        if (rescued.isBlank()) {
            return text;
        }
        return rescued;
    }

    /** 走 DS fetch 补一次；任何人任何一步不满足就返回空串，调用方保持原结果 */
    private String fetchFromDeepSeek(String url) {
        if (deepSeekSearchClient == null || !deepSeekSearchClient.enabled()) {
            return "";
        }
        String userId = statusService.currentUserId();
        if (quota != null && !quota.tryConsume(userId)) {
            return "";
        }
        String content = deepSeekSearchClient.fetch(url);
        if (content == null || content.isBlank()) {
            return "";
        }
        String clipped = content.length() > maxTextChars ? content.substring(0, maxTextChars) : content;
        return clipped + "\n\n（这一段是 DeepSeek 渲染后取回的正文，本地直接抓只拿到空壳页面）";
    }

    private boolean hasCauseMessage(Throwable throwable, String expected) {
        Throwable current = throwable;
        while (current != null) {
            if (expected.equalsIgnoreCase(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String readableSize(int bytes) {
        if (bytes >= 1024 * 1024 && bytes % (1024 * 1024) == 0) {
            return (bytes / (1024 * 1024)) + " MB";
        }
        if (bytes >= 1024 && bytes % 1024 == 0) {
            return (bytes / 1024) + " KB";
        }
        return bytes + " 字节";
    }

    private void status(String text) {
        statusService.push(text);
    }

    private final ToolStatusService statusService;

    @Autowired(required = false)
    public void setDeepSeekFallback(DeepSeekSearchClient deepSeekSearchClient, DeepSeekSearchQuota quota,
                                    @Value("${web.deepseek-fetch-min-chars:300}") int minChars) {
        this.deepSeekSearchClient = deepSeekSearchClient;
        this.quota = quota;
        this.deepSeekFetchMinChars = Math.max(0, minChars);
    }

    @Autowired
    public WebPageTool(ToolStatusService statusService,
                       @Value("${web.max-response-bytes:2097152}") int maxResponseBytes,
                       @Value("${web.max-text-chars:12000}") int maxTextChars,
                       @Value("${web.max-redirects:3}") int maxRedirects,
                       @Value("${web.connect-timeout-seconds:8}") long connectTimeoutSeconds,
                       @Value("${web.read-timeout-seconds:20}") long readTimeoutSeconds,
                       @Value("${web.user-agent:Mozilla/5.0 (compatible; WechatAgent/1.0)}") String userAgent,
                       PublicUrlValidator urlValidator) {
        this.statusService = statusService;
        this.maxResponseBytes = bounded(maxResponseBytes, 1_024, Integer.MAX_VALUE, 2_097_152);
        this.maxTextChars = bounded(maxTextChars, 256, 1_000_000, 12_000);
        this.maxRedirects = bounded(maxRedirects, 0, 10, DEFAULT_MAX_REDIRECTS);
        this.userAgent = userAgent == null || userAgent.isBlank() ? DEFAULT_USER_AGENT : userAgent.trim();
        this.urlValidator = urlValidator;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(bounded(connectTimeoutSeconds, 1, 300, DEFAULT_CONNECT_TIMEOUT_SECONDS)))
                .readTimeout(Duration.ofSeconds(bounded(readTimeoutSeconds, 1, 600, DEFAULT_READ_TIMEOUT_SECONDS)))
                .followRedirects(false)
                .dns(urlValidator::lookupPublic)
                .build();
    }

    WebPageTool(ToolStatusService statusService,
                int maxResponseBytes,
                int maxTextChars,
                PublicUrlValidator urlValidator) {
        this(statusService, maxResponseBytes, maxTextChars, DEFAULT_MAX_REDIRECTS,
                DEFAULT_CONNECT_TIMEOUT_SECONDS, DEFAULT_READ_TIMEOUT_SECONDS, DEFAULT_USER_AGENT, urlValidator);
    }

    private String readBody(Response response) throws IOException {
        long contentLength = response.body().contentLength();
        if (contentLength > maxResponseBytes) {
            throw new IOException("response too large");
        }
        try (var input = response.body().byteStream(); var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxResponseBytes) {
                    throw new IOException("response too large");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /**
     * 供其它工具复用（搜索的"深入读原文"）：静默抓取正文。
     * 失败一律返回 {@code null}，不推送状态、不抛异常——抓不到就退化为只用搜索摘要。
     */
    public String fetchTextQuietly(String url, int maxChars) {
        if (url == null || url.isBlank()) {
            return null;
        }
        int limit = Math.max(256, Math.min(20_000, maxChars));
        try {
            URI current = urlValidator.validate(url);
            for (int redirects = 0; redirects <= maxRedirects; redirects++) {
                Request request = new Request.Builder()
                        .url(current.toString())
                        .header("User-Agent", userAgent)
                        .header("Accept", "text/html,text/plain,application/json;q=0.9,*/*;q=0.1")
                        .get()
                        .build();
                try (Response response = client.newCall(request).execute()) {
                    if (response.isRedirect()) {
                        String location = response.header("Location");
                        if (location == null || location.isBlank()) {
                            return null;
                        }
                        current = urlValidator.validate(current.resolve(location).toString());
                        continue;
                    }
                    if (!response.isSuccessful() || response.body() == null) {
                        return null;
                    }
                    String contentType = response.header("Content-Type", "").toLowerCase();
                    if (!contentType.startsWith("text/") && !contentType.contains("json")) {
                        return null;
                    }
                    String text = extractPlainText(contentType, readBody(response));
                    if (text == null || text.isBlank()) {
                        return null;
                    }
                    String compact = text.replaceAll("\\s*\\n\\s*", "\n").replaceAll("[ \\t]{2,}", " ").strip();
                    return compact.length() <= limit ? compact : compact.substring(0, limit) + "…";
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    /** 与 {@link #extractText} 同一套清洗逻辑，但只返回纯正文（不含"网页/标题"包装）。 */
    private String extractPlainText(String contentType, String body) {
        if (contentType.contains("html")) {
            var document = Jsoup.parse(body);
            document.select("script, style, noscript, svg, nav, footer, header, aside").remove();
            return document.body() == null ? "" : document.body().text();
        }
        return body;
    }

    private String extractText(String url, String contentType, String body) {
        String text;
        String title = "";
        if (contentType.contains("html")) {
            var document = Jsoup.parse(body, url);
            document.select("script, style, noscript, svg, nav, footer, header, aside").remove();
            title = document.title();
            text = document.body() == null ? "" : document.body().text();
        } else {
            text = body;
        }
        if (text.isBlank()) {
            return "网页已打开，但没有提取到可读正文。";
        }
        String clipped = text.length() <= maxTextChars ? text : text.substring(0, maxTextChars) + "\n[网页正文过长，已截断]";
        return "网页：" + url + (title.isBlank() ? "" : "\n标题：" + title) + "\n正文：\n" + clipped;
    }

    private static int bounded(int value, int minimum, int maximum, int fallback) {
        return value < minimum || value > maximum ? fallback : value;
    }

    private static long bounded(long value, long minimum, long maximum, long fallback) {
        return value < minimum || value > maximum ? fallback : value;
    }
}
