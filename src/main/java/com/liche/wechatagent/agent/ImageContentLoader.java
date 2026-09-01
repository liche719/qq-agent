package com.liche.wechatagent.agent;

import com.liche.wechatagent.network.PublicUrlValidator;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Base64;
import java.util.Locale;

/** Downloads, validates, and converts remote images into model-ready data URLs. */
final class ImageContentLoader {

    private final PublicUrlValidator urlValidator;
    private final long maxImageBytes;
    private final OkHttpClient client;
    private final int maxRedirects;
    private final String userAgent;

    ImageContentLoader(PublicUrlValidator urlValidator, long maxImageBytes, OkHttpClient client,
                       int maxRedirects, String userAgent) {
        this.urlValidator = urlValidator;
        this.maxImageBytes = maxImageBytes;
        this.client = client;
        this.maxRedirects = maxRedirects;
        this.userAgent = userAgent;
    }

    String load(String url) {
        if (url == null || url.isBlank()) return null;
        if (url.startsWith("data:")) return normalizeDataUrl(url);
        try {
            URI current = urlValidator.validate(url);
            for (int redirects = 0; redirects <= maxRedirects; redirects++) {
                Request request = new Request.Builder().url(current.toString())
                        .header("User-Agent", userAgent).get().build();
                try (Response response = client.newCall(request).execute()) {
                    if (response.isRedirect()) {
                        String location = response.header("Location");
                        if (location == null || location.isBlank()) return null;
                        current = urlValidator.validate(current.resolve(location).toString());
                        continue;
                    }
                    if (!response.isSuccessful() || response.body() == null) return null;
                    String contentType = normalizeContentType(response.header("Content-Type"));
                    if (contentType == null) return null;
                    byte[] bytes = readImage(response.body().byteStream(), response.body().contentLength());
                    return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes);
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private String normalizeDataUrl(String value) {
        int comma = value.indexOf(',');
        if (comma < 0) return null;
        String metadata = value.substring(5, comma);
        String contentType = normalizeContentType(metadata.split(";", 2)[0]);
        if (contentType == null || !metadata.toLowerCase(Locale.ROOT).contains(";base64")) return null;
        String encoded = value.substring(comma + 1);
        if (encoded.length() > (maxImageBytes * 4 / 3) + 8) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length == 0 || bytes.length > maxImageBytes) return null;
            return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private byte[] readImage(InputStream input, long declaredLength) throws IOException {
        if (declaredLength > maxImageBytes) throw new IllegalArgumentException("图片超过大小限制");
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxImageBytes) throw new IllegalArgumentException("图片超过大小限制");
                output.write(buffer, 0, read);
            }
            if (total == 0) throw new IllegalArgumentException("图片为空");
            return output.toByteArray();
        }
    }

    private String normalizeContentType(String value) {
        if (value == null || value.isBlank()) return null;
        return switch (value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT)) {
            case "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp" ->
                    value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            default -> null;
        };
    }
}
