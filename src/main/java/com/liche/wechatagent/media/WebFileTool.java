package com.liche.wechatagent.media;

import com.liche.wechatagent.channel.OutboundMedia;
import com.liche.wechatagent.network.PublicUrlValidator;
import com.liche.wechatagent.tool.ToolStatusService;
import com.liche.wechatagent.tool.ToolExecutionPolicy;
import com.liche.wechatagent.tool.ToolExecutionClass;
import com.liche.wechatagent.tool.ToolRiskLevel;
import dev.langchain4j.agent.tool.Tool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
public class WebFileTool {

    private static final int DEFAULT_MAX_REDIRECTS = 3;
    private static final int DEFAULT_MAX_LINKS = 20;
    private static final long DEFAULT_MAX_RESPONSE_BYTES = 2_097_152L;
    private static final long DEFAULT_CONNECT_TIMEOUT_SECONDS = 8;
    private static final long DEFAULT_READ_TIMEOUT_SECONDS = 20;
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (compatible; WechatAgent/1.0)";

    private final MediaStorageService storageService;
    private final ToolStatusService statusService;
    private final MediaToolContextService mediaContext;
    private final PublicUrlValidator urlValidator;
    private final OkHttpClient client;
    private final long maxResponseBytes;
    private final int maxRedirects;
    private final int maxLinks;
    private final String userAgent;

    @Autowired
    public WebFileTool(MediaStorageService storageService, ToolStatusService statusService,
                       MediaToolContextService mediaContext,
                       PublicUrlValidator urlValidator,
                       @Value("${web.max-response-bytes:2097152}") long maxResponseBytes,
                       @Value("${web.max-redirects:3}") int maxRedirects,
                       @Value("${web.max-links:20}") int maxLinks,
                       @Value("${web.connect-timeout-seconds:8}") long connectTimeoutSeconds,
                       @Value("${web.read-timeout-seconds:20}") long readTimeoutSeconds,
                       @Value("${web.user-agent:Mozilla/5.0 (compatible; WechatAgent/1.0)}") String userAgent) {
        this.storageService = storageService;
        this.statusService = statusService;
        this.mediaContext = mediaContext;
        this.urlValidator = urlValidator;
        this.maxResponseBytes = bounded(maxResponseBytes, 1_024, Long.MAX_VALUE, DEFAULT_MAX_RESPONSE_BYTES);
        this.maxRedirects = bounded(maxRedirects, 0, 10, DEFAULT_MAX_REDIRECTS);
        this.maxLinks = bounded(maxLinks, 1, 100, DEFAULT_MAX_LINKS);
        this.userAgent = userAgent == null || userAgent.isBlank() ? DEFAULT_USER_AGENT : userAgent.trim();
        this.client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(bounded(connectTimeoutSeconds, 1, 300, DEFAULT_CONNECT_TIMEOUT_SECONDS)))
                .readTimeout(Duration.ofSeconds(bounded(readTimeoutSeconds, 1, 600, DEFAULT_READ_TIMEOUT_SECONDS)))
                .followRedirects(false)
                .dns(urlValidator::lookupPublic)
                .build();
    }

    WebFileTool(MediaStorageService storageService, ToolStatusService statusService,
                PublicUrlValidator urlValidator) {
        this(storageService, statusService, new MediaToolContextService(), urlValidator, DEFAULT_MAX_RESPONSE_BYTES,
                DEFAULT_MAX_REDIRECTS, DEFAULT_MAX_LINKS, DEFAULT_CONNECT_TIMEOUT_SECONDS,
                DEFAULT_READ_TIMEOUT_SECONDS, DEFAULT_USER_AGENT);
    }

    @Tool(value = "列出公开网页中可直接下载的文件链接。仅当用户明确要求寻找或下载该网页的文件时调用。返回链接后，必须根据用户指定的文件调用 downloadWebFile；不得下载登录、付费、版权受限或用户未要求的内容。")
    @ToolExecutionPolicy(ToolExecutionClass.SLOW_EXTERNAL)
    public String findDownloadableLinks(String pageUrl) {
        statusService.push("我正在查找网页里的可下载文件…");
        try {
            URI current = urlValidator.validate(pageUrl);
            for (int redirects = 0; redirects <= maxRedirects; redirects++) {
                Request request = new Request.Builder().url(current.toString())
                        .header("User-Agent", userAgent).get().build();
                try (Response response = client.newCall(request).execute()) {
                    if (response.isRedirect()) {
                        String location = response.header("Location");
                        if (location == null || location.isBlank()) throw new IllegalStateException("网页跳转失败，未提供目标地址");
                        current = urlValidator.validate(current.resolve(location).toString());
                        continue;
                    }
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IllegalStateException("网页访问失败：HTTP " + response.code());
                    }
                    String contentType = response.header("Content-Type", "").toLowerCase();
                    if (!contentType.contains("html")) throw new IllegalStateException("这个链接不是网页，不能从中提取下载链接");
                    String html = readHtml(response.body().byteStream(), response.body().contentLength());
                    return collectLinks(current, html);
                }
            }
            throw new IllegalStateException("网页跳转次数过多，无法继续查找下载链接");
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("网页地址不安全或格式不正确", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("网页暂时无法访问，无法查找下载链接", exception);
        }
    }

    @Tool(value = "下载用户当前明确指定的公开文件 URL，并保存到该用户独立目录。fileName 应按文件内容命名，summary 应说明文件是什么。下载完成后如用户要求发送，再调用 sendDownloadedFile。不得下载或转发登录、付费、版权受限或用户未明确要求的内容。")
    @ToolExecutionPolicy(ToolExecutionClass.SLOW_EXTERNAL)
    @com.liche.wechatagent.tool.NonIdempotentTool
    public String downloadWebFile(String url, String fileName, String summary) {
        statusService.push("我正在下载这个文件…");
        String userId = requireCurrentUser();
        return storageService.downloadFromWeb(userId, null, url, fileName, summary).message();
    }

    @Tool(value = "将当前用户已下载或保存的本地文件发送到当前消息通道。先用 listStoredMedia 找到 mediaId；仅当用户当前明确要求发送该文件时调用。不能发送其他用户文件。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, riskLevel = ToolRiskLevel.MEDIUM, allowParallel = false)
    @com.liche.wechatagent.tool.NonIdempotentTool
    public String sendDownloadedFile(Long mediaId) {
        statusService.push("我正在发送文件…");
        MediaStorageService.SendableMedia media = storageService.requireSendableMedia(requireCurrentUser(), mediaId);
        boolean sent = statusService.sendMedia(new OutboundMedia(media.localFile(), media.fileName(), media.contentType()));
        if (!sent) {
            throw new IllegalStateException("当前通道不支持发送文件，或 QQ 文件上传失败");
        }
        mediaContext.recordSent(media.fileName());
        return "文件已发送：" + media.fileName();
    }

    private String collectLinks(URI pageUrl, String html) {
        Set<String> links = new LinkedHashSet<>();
        for (Element anchor : Jsoup.parse(html, pageUrl.toString()).select("a[href]")) {
            String href = anchor.absUrl("href");
            if (href.isBlank()) continue;
            try {
                URI link = urlValidator.validate(href);
                if (looksDownloadable(href, anchor)
                        || (isContentAreaLink(anchor) && hasDownloadResponse(link, pageUrl))) {
                    links.add(link.toString());
                }
            } catch (IllegalArgumentException ignored) {
            }
            if (links.size() >= maxLinks) break;
        }
        if (links.isEmpty()) return "没有发现可直接下载的公开文件链接。";
        StringBuilder result = new StringBuilder("发现以下可下载文件：\n");
        int index = 1;
        for (String link : links) result.append(index++).append(". ").append(link).append('\n');
        return result.toString().trim();
    }

    private String readHtml(InputStream input, long declaredLength) {
        if (declaredLength > maxResponseBytes) {
            throw new IllegalStateException("网页内容过大，无法安全读取下载链接");
        }
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxResponseBytes) {
                    throw new IllegalStateException("网页内容过大，无法安全读取下载链接");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("读取网页内容失败", exception);
        }
    }

    private boolean looksDownloadable(String href, Element anchor) {
        String lower = href.toLowerCase();
        String linkText = anchor.text().trim().toLowerCase();
        return anchor.hasAttr("download")
                || lower.contains("/download")
                || lower.contains("download.jsp")
                || lower.matches(".*\\.(pdf|docx?|xlsx?|pptx?|zip|rar|7z|txt|csv|jpg|jpeg|png|gif|webp|mp4|mp3)(\\?.*)?$")
                || linkText.matches(".*\\.(pdf|docx?|xlsx?|pptx?|zip|rar|7z|txt|csv|jpg|jpeg|png|gif|webp|mp4|mp3)$")
                || anchor.parents().stream().anyMatch(parent -> parent.hasClass("Annex"));
    }

    private boolean isContentAreaLink(Element anchor) {
        return anchor.parents().stream().anyMatch(parent -> "vsb_content".equals(parent.id())
                || parent.hasClass("Annex") || parent.hasClass("article") || parent.hasClass("content"));
    }

    private boolean hasDownloadResponse(URI link, URI pageUrl) {
        URI current = link;
        for (int redirects = 0; redirects <= maxRedirects; redirects++) {
            Request request = new Request.Builder().url(current.toString())
                    .header("User-Agent", userAgent)
                    .header("Referer", pageUrl.getScheme() + "://" + pageUrl.getHost() + "/")
                    .header("Range", "bytes=0-0")
                    .get().build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isRedirect()) {
                    String location = response.header("Location");
                    if (location == null || location.isBlank()) return false;
                    current = urlValidator.validate(current.resolve(location).toString());
                    continue;
                }
                String contentType = response.header("Content-Type", "").toLowerCase();
                String disposition = response.header("Content-Disposition", "").toLowerCase();
                return response.isSuccessful() && (disposition.contains("attachment")
                        || (!contentType.contains("html") && !contentType.startsWith("text/")));
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    private String requireCurrentUser() {
        String userId = statusService.currentUserId();
        if (userId == null || userId.isBlank()) throw new IllegalStateException("当前用户上下文不存在");
        return userId;
    }

    private static int bounded(int value, int minimum, int maximum, int fallback) {
        return value < minimum || value > maximum ? fallback : value;
    }

    private static long bounded(long value, long minimum, long maximum, long fallback) {
        return value < minimum || value > maximum ? fallback : value;
    }
}
