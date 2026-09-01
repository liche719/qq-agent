package com.liche.wechatagent.media;

import com.liche.wechatagent.network.PublicUrlValidator;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;

/** Handles untrusted URL validation, redirects, data URLs, and bounded downloads. */
final class MediaDownloadService {

    record DownloadedMedia(byte[] bytes, String contentType) {
    }

    private final PublicUrlValidator urlValidator;
    private final long maxFileBytes;
    private final int maxRedirects;
    private final String userAgent;
    private final OkHttpClient client;

    // Creates a bounded downloader with public-URL validation.
    MediaDownloadService(PublicUrlValidator urlValidator, long maxFileBytes, int maxRedirects,
                          long connectTimeoutSeconds, long readTimeoutSeconds, String userAgent) {
        this.urlValidator = urlValidator;
        this.maxFileBytes = maxFileBytes;
        this.maxRedirects = maxRedirects;
        this.userAgent = userAgent;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .readTimeout(Duration.ofSeconds(readTimeoutSeconds))
                .followRedirects(false)
                .dns(urlValidator::lookupPublic)
                .build();
    }

    // Downloads a candidate from a data URL or validated HTTP redirect chain.
    DownloadedMedia download(MediaCandidate candidate) {
        String source = candidate.sourceUrl();
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("当前媒体没有可用下载地址");
        }
        if (source.startsWith("data:")) {
            return decodeDataUrl(source, candidate.contentType());
        }
        URI current;
        try {
            current = urlValidator.validate(source);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("媒体下载地址无效或不安全", exception);
        }
        for (int redirects = 0; redirects <= maxRedirects; redirects++) {
            Request request = new Request.Builder().url(current.toString())
                    .header("User-Agent", userAgent)
                    .header("Referer", current.getScheme() + "://" + current.getHost() + "/")
                    .get().build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isRedirect()) {
                    String location = response.header("Location");
                    if (location == null || location.isBlank()) {
                        throw new IllegalStateException("媒体下载跳转地址为空");
                    }
                    current = urlValidator.validate(current.resolve(location).toString());
                    continue;
                }
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IllegalStateException("媒体下载失败，HTTP " + response.code());
                }
                String contentType = normalizeContentType(response.header("Content-Type"), candidate.contentType());
                if (contentType.contains("text/html")) {
                    throw new IllegalStateException("下载结果是网页或登录提示，不是可保存的文件");
                }
                return new DownloadedMedia(readLimited(response.body().byteStream(), response.body().contentLength()), contentType);
            } catch (IOException exception) {
                throw new IllegalStateException("媒体下载失败，请重新发送", exception);
            }
        }
        throw new IllegalStateException("媒体下载跳转次数过多");
    }

    // Decodes an inline data URL while enforcing the configured size limit.
    private DownloadedMedia decodeDataUrl(String value, String fallbackContentType) {
        int comma = value.indexOf(',');
        if (comma < 0) {
            throw new IllegalArgumentException("图片 data URL 格式无效");
        }
        String metadata = value.substring(5, comma);
        String encoded = value.substring(comma + 1);
        String contentType = normalizeContentType(metadata.split(";", 2)[0], fallbackContentType);
        try {
            if (metadata.toLowerCase(Locale.ROOT).contains(";base64")
                    && encoded.length() > (maxFileBytes * 4 / 3) + 8) {
                throw new IllegalArgumentException("文件超过允许保存的大小");
            }
            byte[] decoded = metadata.toLowerCase(Locale.ROOT).contains(";base64")
                    ? Base64.getDecoder().decode(encoded)
                    : java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
            return new DownloadedMedia(readLimited(new ByteArrayInputStream(decoded), decoded.length), contentType);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("图片 data URL 无法解码", exception);
        }
    }

    // Reads a stream without allowing an untrusted source to exceed the limit.
    private byte[] readLimited(InputStream input, long declaredLength) {
        if (declaredLength > maxFileBytes) {
            throw new IllegalArgumentException("文件超过允许保存的大小");
        }
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxFileBytes) {
                    throw new IllegalArgumentException("文件超过允许保存的大小");
                }
                output.write(buffer, 0, read);
            }
            if (total == 0) {
                throw new IllegalArgumentException("不能保存空文件");
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("读取媒体内容失败", exception);
        }
    }

    // Normalizes MIME values and applies the candidate fallback type.
    private String normalizeContentType(String primary, String fallback) {
        String value = primary == null || primary.isBlank() ? fallback : primary;
        if (value == null || value.isBlank()) return "application/octet-stream";
        return value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }
}
