package com.liche.wechatagent.search;

import com.liche.wechatagent.tool.ToolStatusService;
import com.liche.wechatagent.network.PublicUrlValidator;
import dev.langchain4j.agent.tool.Tool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;

@Component
public class WebPageTool {

    private static final int MAX_REDIRECTS = 3;

    private final OkHttpClient client;
    private final int maxResponseBytes;
    private final int maxTextChars;
    private final PublicUrlValidator urlValidator;

    @Tool(value = "读取指定公开网页的正文。当用户给出 URL，或需要查看搜索结果中的具体页面、文章详情时调用。只支持公开 HTTP/HTTPS 页面。")
    public String readWebPage(String url) {
        status("我正在打开这个网页…");
        try {
            URI current = urlValidator.validate(url);
            for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
                Request request = new Request.Builder()
                        .url(current.toString())
                        .header("User-Agent", "Mozilla/5.0 (compatible; WechatAgent/1.0)")
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
            throw new IllegalStateException("网页暂时无法访问，请稍后重试或换一个链接", exception);
        }
    }

    private void status(String text) {
        statusService.push(text);
    }

    private final ToolStatusService statusService;

    public WebPageTool(ToolStatusService statusService,
                       @Value("${web.max-response-bytes:2097152}") int maxResponseBytes,
                       @Value("${web.max-text-chars:12000}") int maxTextChars,
                       PublicUrlValidator urlValidator) {
        this.statusService = statusService;
        this.maxResponseBytes = maxResponseBytes;
        this.maxTextChars = maxTextChars;
        this.urlValidator = urlValidator;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(8))
                .readTimeout(Duration.ofSeconds(20))
                .followRedirects(false)
                .dns(urlValidator::lookupPublic)
                .build();
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
}
