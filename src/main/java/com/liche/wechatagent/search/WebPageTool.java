package com.liche.wechatagent.search;

import com.liche.wechatagent.tool.ToolStatusService;
import com.liche.wechatagent.network.PublicUrlValidator;
import com.liche.wechatagent.tool.ToolExecutionPolicy;
import com.liche.wechatagent.tool.ToolExecutionClass;
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
public class WebPageTool {

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

    @Tool(value = "读取指定公开网页的正文。当用户给出 URL，或需要查看搜索结果中的具体页面、文章详情时调用。只支持公开 HTTP/HTTPS 页面。")
    @ToolExecutionPolicy(ToolExecutionClass.SLOW_EXTERNAL)
    public String readWebPage(String url) {
        status("我正在打开这个网页…");
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
                            throw new IllegalStateException("网页跳转失败：服务端没有提供跳转地址");
                        }
                        current = urlValidator.validate(current.resolve(location).toString());
                        continue;
                    }
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IllegalStateException("网页访问失败：HTTP " + response.code());
                    }
                    String contentType = response.header("Content-Type", "").toLowerCase();
                    if (!contentType.startsWith("text/") && !contentType.contains("json")) {
                        throw new IllegalStateException("这个链接不是可读取的网页文本，当前只支持 HTML、纯文本或 JSON 页面");
                    }
                    String body = readBody(response);
                    return extractText(current.toString(), contentType, body);
                }
            }
            throw new IllegalStateException("网页跳转次数过多，无法继续访问");
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("这个链接不安全或格式不正确，无法访问", exception);
        } catch (Exception exception) {
            if (hasCauseMessage(exception, "response too large")) {
                throw new IllegalStateException("网页内容超过安全读取上限（"
                        + readableSize(maxResponseBytes) + "），无法读取", exception);
            }
            throw new IllegalStateException("网页暂时无法访问，请稍后重试或换一个链接", exception);
        }
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
