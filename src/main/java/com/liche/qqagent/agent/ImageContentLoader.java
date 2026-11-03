package com.liche.qqagent.agent;

import com.liche.qqagent.network.PublicUrlValidator;
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
    /** 送进视觉模型前把长边压到多少像素（2026-11-04）：视觉 token 按像素算，缩一半约省一大半时间 */
    private final int maxSidePhoto;
    private final int maxSideDoc;

    ImageContentLoader(PublicUrlValidator urlValidator, long maxImageBytes, OkHttpClient client,
                       int maxRedirects, String userAgent) {
        this(urlValidator, maxImageBytes, client, maxRedirects, userAgent, 1280, 1600);
    }

    ImageContentLoader(PublicUrlValidator urlValidator, long maxImageBytes, OkHttpClient client,
                       int maxRedirects, String userAgent, int maxSidePhoto, int maxSideDoc) {
        this.urlValidator = urlValidator;
        this.maxImageBytes = maxImageBytes;
        this.client = client;
        this.maxRedirects = maxRedirects;
        this.userAgent = userAgent;
        this.maxSidePhoto = Math.max(512, maxSidePhoto);
        this.maxSideDoc = Math.max(this.maxSidePhoto, maxSideDoc);
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
                    byte[] prepared = shrink(bytes);
                    String type = prepared == bytes ? contentType : "image/jpeg";
                    return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(prepared);
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    /**
     * 把图缩到合理尺寸（2026-11-04）。
     *
     * <p><b>为什么</b>：视觉模型的 token 按**像素**算，一张 2000×3000 的图会切出几千个视觉 token，
     * 首跳实测 28 秒。缩到长边 1280 后 token 大约降到 1/3。
     *
     * <p><b>为什么要分两档</b>：照片缩狠了看不出差别，但**截图/文档上的小字**缩狠了就糊，
     * 所以长宽比很夸张（高于 1.6 或低于 0.6）的当成截图，保留到 1600。
     *
     * <p>解码失败（动图、WebP 等 ImageIO 不支持的格式）就原样返回，不冒险。
     */
    private byte[] shrink(byte[] original) {
        try {
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(original));
            if (image == null) {
                return original;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            int longest = Math.max(width, height);
            double ratio = width / (double) Math.max(1, height);
            int limit = (ratio > 1.6 || ratio < 0.6) ? maxSideDoc : maxSidePhoto;
            if (longest <= limit) {
                return original;
            }
            double scale = limit / (double) longest;
            int targetWidth = Math.max(1, (int) Math.round(width * scale));
            int targetHeight = Math.max(1, (int) Math.round(height * scale));
            java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(
                    targetWidth, targetHeight, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D graphics = scaled.createGraphics();
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                    java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(image, 0, 0, targetWidth, targetHeight, java.awt.Color.WHITE, null);
            graphics.dispose();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(scaled, "jpg", out);
            byte[] compressed = out.toByteArray();
            // 压完反而更大就别用（小图 + 高质量重编码可能变大）
            return compressed.length < original.length ? compressed : original;
        } catch (Exception ignored) {
            return original;
        }
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
