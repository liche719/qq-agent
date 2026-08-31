package com.liche.wechatagent.media;

import com.liche.wechatagent.network.PublicUrlValidator;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MediaStorageService {

    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_LIST_RESULTS = 20;
    private static final int MAX_INSPECTION_TEXT_CHARS = 4000;

    private record DownloadedMedia(byte[] bytes, String contentType) {
    }

    private record InspectionGrant(String token, Instant expiresAt) {
    }

    public record SaveOutcome(String message, String fileName, boolean duplicate, boolean olderVersionPreserved) {
    }

    public record ReadOutcome(String description, String imageDataUrl) {
    }

    public record SendableMedia(Path localFile, String fileName, String contentType) {
    }

    private final StoredMediaRepository repository;
    private final PublicUrlValidator urlValidator;
    private final Path storageRoot;
    private final long maxFileBytes;
    private final Duration inspectionTtl;
    private final OkHttpClient client;
    private final Map<String, InspectionGrant> inspectionGrants = new ConcurrentHashMap<>();

    public MediaStorageService(StoredMediaRepository repository,
                               PublicUrlValidator urlValidator,
                               @Value("${media.storage.root:stored-media}") String storageRoot,
                               @Value("${media.storage.max-file-bytes:20971520}") long maxFileBytes,
                               @Value("${media.storage.inspection-token-minutes:5}") long inspectionTokenMinutes) {
        this.repository = repository;
        this.urlValidator = urlValidator;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
        this.maxFileBytes = maxFileBytes;
        this.inspectionTtl = Duration.ofMinutes(Math.max(1, inspectionTokenMinutes));
        this.client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(8))
                .readTimeout(Duration.ofSeconds(30))
                .followRedirects(false)
                .dns(urlValidator::lookupPublic)
                .build();
    }

    @Transactional
    public synchronized String save(String userId, String sourceMessageId, MediaCandidate candidate,
                                    String requestedName, String summary, String importanceReason) {
        return saveDetailed(userId, sourceMessageId, candidate, requestedName, summary, importanceReason).message();
    }

    @Transactional
    public synchronized SaveOutcome saveDetailed(String userId, String sourceMessageId, MediaCandidate candidate,
                                                 String requestedName, String summary, String importanceReason) {
        requireUserId(userId);
        requireText(summary, "内容摘要", 4);
        requireText(importanceReason, "重要性原因", 4);
        DownloadedMedia downloaded = download(candidate);
        String sha256 = sha256(downloaded.bytes());
        var duplicate = repository.findFirstByUserIdAndSha256AndStatus(userId, sha256, StoredMedia.ACTIVE);
        if (duplicate.isPresent()) {
            StoredMedia existing = duplicate.get();
            return new SaveOutcome("这个文件已经保存过了：ID=" + existing.getId()
                    + "，文件名=" + existing.getFileName(), existing.getFileName(), true, false);
        }

        String extension = extensionOf(candidate.originalName(), downloaded.contentType());
        Path activeDirectory = userRoot(userId).resolve("active");
        try {
            Files.createDirectories(activeDirectory);
            String requestedBase = baseName(sanitizeFileName(requestedName));
            boolean olderVersionPreserved = repository
                    .findByUserIdAndStatusOrderByUpdatedAtDesc(userId, StoredMedia.ACTIVE).stream()
                    .map(StoredMedia::getFileName)
                    .map(this::baseName)
                    .anyMatch(existingBase -> existingBase.equalsIgnoreCase(requestedBase));
            String fileName = availableFileName(activeDirectory, requestedName, extension);
            Path target = checkedResolve(activeDirectory, fileName);
            Path temporary = Files.createTempFile(activeDirectory, ".saving-", ".tmp");
            try {
                Files.write(temporary, downloaded.bytes());
                move(temporary, target);
            } finally {
                Files.deleteIfExists(temporary);
            }
            String relativePath = storageRoot.relativize(target).toString().replace('\\', '/');
            StoredMedia media = new StoredMedia(userId, fileName, candidate.originalName(), downloaded.contentType(),
                    relativePath, sha256, downloaded.bytes().length, summary.trim(), importanceReason.trim(),
                    blankToEmpty(candidate.extractedText()), sourceMessageId);
            media.setSourceUrl(candidate.sourceUrl());
            try {
                repository.save(media);
            } catch (RuntimeException exception) {
                Files.deleteIfExists(target);
                throw exception;
            }
            return new SaveOutcome("已长期保存：ID=" + media.getId() + "，文件名=" + media.getFileName()
                    + "，摘要=" + media.getSummary(), media.getFileName(), false, olderVersionPreserved);
        } catch (IOException exception) {
            throw new IllegalStateException("文件保存失败，请稍后重试", exception);
        }
    }

    @Transactional(readOnly = true)
    public String list(String userId, String query) {
        requireUserId(userId);
        String keyword = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<StoredMedia> matches = repository.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, StoredMedia.ACTIVE)
                .stream()
                .filter(media -> keyword.isBlank() || searchableText(media).contains(keyword))
                .sorted(Comparator.comparing(StoredMedia::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_LIST_RESULTS)
                .toList();
        if (matches.isEmpty()) {
            return "没有找到匹配的已保存文件。";
        }
        StringBuilder result = new StringBuilder("当前用户已保存的文件：\n");
        for (StoredMedia media : matches) {
            result.append("- ID=").append(media.getId())
                    .append("，文件名=").append(media.getFileName())
                    .append(blankToEmpty(media.getOriginalName()).isBlank() ? "" : "，原文件名=" + media.getOriginalName())
                    .append("，保存于=").append(formatTime(media.getCreatedAt()))
                    .append("，最近更新=").append(formatTime(media.getUpdatedAt()))
                    .append("，摘要=").append(media.getSummary()).append('\n');
        }
        return result.toString().trim();
    }

    @Transactional(readOnly = true)
    public String inspect(String userId, Long mediaId) {
        StoredMedia media = requireOwnedActive(userId, mediaId);
        Path file = resolveStoredPath(media);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("文件记录存在，但磁盘文件已丢失，不能删除");
        }
        String token = UUID.randomUUID().toString();
        Instant now = Instant.now();
        inspectionGrants.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        inspectionGrants.put(grantKey(userId, mediaId), new InspectionGrant(token, now.plus(inspectionTtl)));
        String extractedText = blankToEmpty(media.getExtractedText());
        if (extractedText.length() > MAX_INSPECTION_TEXT_CHARS) {
            extractedText = extractedText.substring(0, MAX_INSPECTION_TEXT_CHARS) + "…（内容已截断）";
        }
        return "文件审阅结果：\n"
                + "ID=" + media.getId() + "\n"
                + "文件名=" + media.getFileName() + "\n"
                + "原文件名=" + blankToEmpty(media.getOriginalName()) + "\n"
                + "类型=" + blankToEmpty(media.getContentType()) + "，大小=" + media.getSizeBytes() + " 字节\n"
                + "保存时间=" + formatTime(media.getCreatedAt()) + "，最近更新=" + formatTime(media.getUpdatedAt()) + "\n"
                + "内容摘要=" + blankToEmpty(media.getSummary()) + "\n"
                + "保存原因=" + blankToEmpty(media.getImportanceReason()) + "\n"
                + (extractedText.isBlank() ? "" : "已提取内容=\n" + extractedText + "\n")
                + "确认这是要删除的内容后，使用 inspectionToken=" + token + " 调用删除工具。令牌 "
                + inspectionTtl.toMinutes() + " 分钟内有效。";
    }

    @Transactional(readOnly = true)
    public ReadOutcome readForAssistant(String userId, Long mediaId) {
        StoredMedia media = requireOwnedActive(userId, mediaId);
        Path file = resolveStoredPath(media);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("文件记录存在，但磁盘文件已丢失，无法读取");
        }
        String extracted = blankToEmpty(media.getExtractedText());
        if (extracted.length() > MAX_INSPECTION_TEXT_CHARS) {
            extracted = extracted.substring(0, MAX_INSPECTION_TEXT_CHARS) + "…（内容已截断）";
        }
        String description = "已读取用户长期保存的文件：\nID=" + media.getId()
                + "\n文件名=" + media.getFileName()
                + "\n保存时间=" + formatTime(media.getCreatedAt())
                + "\n最近更新=" + formatTime(media.getUpdatedAt())
                + "\n内容摘要=" + blankToEmpty(media.getSummary())
                + (extracted.isBlank() ? "" : "\n已提取内容=\n" + extracted);
        if (media.getContentType() == null || !media.getContentType().toLowerCase(Locale.ROOT).startsWith("image/")) {
            return new ReadOutcome(description, null);
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0 || bytes.length > maxFileBytes) {
                throw new IllegalStateException("已保存图片大小异常，无法读取");
            }
            return new ReadOutcome(description + "\n这是一张图片，已重新提供给视觉模型查看。",
                    "data:" + normalizeContentType(media.getContentType(), "image/jpeg") + ";base64,"
                            + Base64.getEncoder().encodeToString(bytes));
        } catch (IOException exception) {
            throw new IllegalStateException("读取已保存图片失败", exception);
        }
    }

    @Transactional
    public SaveOutcome downloadFromWeb(String userId, String sourceMessageId, String url, String fileName, String summary) {
        requireUserId(userId);
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("下载地址不能为空");
        }
        String requestedName = fileName == null || fileName.isBlank() ? "下载资料" : fileName;
        return saveDetailed(userId, sourceMessageId,
                new MediaCandidate(1, requestedName, "application/octet-stream", url.trim(), "", false),
                requestedName, summary, "用户当前明确要求从公开网页下载这份资料，保存后可再次发送或查看。");
    }

    @Transactional(readOnly = true)
    public SendableMedia requireSendableMedia(String userId, Long mediaId) {
        StoredMedia media = requireOwnedActive(userId, mediaId);
        Path file = resolveStoredPath(media);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("文件记录存在，但磁盘文件已丢失，无法发送");
        }
        return new SendableMedia(file, media.getFileName(), media.getContentType());
    }

    @Transactional
    public synchronized String trash(String userId, Long mediaId, String inspectionToken, String reason) {
        requireText(reason, "删除原因", 4);
        StoredMedia media = requireOwnedActive(userId, mediaId);
        String key = grantKey(userId, mediaId);
        InspectionGrant grant = inspectionGrants.get(key);
        if (grant == null || grant.expiresAt().isBefore(Instant.now())
                || inspectionToken == null || !MessageDigest.isEqual(
                grant.token().getBytes(StandardCharsets.UTF_8), inspectionToken.getBytes(StandardCharsets.UTF_8))) {
            inspectionGrants.remove(key);
            throw new IllegalStateException("删除前必须先审阅该文件；审阅令牌无效或已过期");
        }

        Path source = resolveStoredPath(media);
        Path trashDirectory = userRoot(userId).resolve(".trash");
        try {
            if (!Files.isRegularFile(source)) {
                throw new IllegalStateException("磁盘文件不存在，已停止删除");
            }
            Files.createDirectories(trashDirectory);
            Path target = checkedResolve(trashDirectory, availableFileName(trashDirectory, media.getFileName(),
                    extensionOf(media.getFileName(), media.getContentType())));
            move(source, target);
            try {
                media.setRelativePath(storageRoot.relativize(target).toString().replace('\\', '/'));
                media.setStatus(StoredMedia.TRASHED);
                media.setTrashReason(reason.trim());
                media.setTrashedAt(LocalDateTime.now());
                media.setUpdatedAt(LocalDateTime.now());
                repository.save(media);
            } catch (RuntimeException exception) {
                move(target, source);
                throw exception;
            }
            inspectionGrants.remove(key);
            return "已移入当前用户的回收目录（未永久删除）：ID=" + media.getId()
                    + "，文件名=" + media.getFileName() + "，原因=" + reason.trim();
        } catch (IOException exception) {
            throw new IllegalStateException("文件移入回收目录失败，未执行删除", exception);
        }
    }

    private DownloadedMedia download(MediaCandidate candidate) {
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
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            Request request = new Request.Builder().url(current.toString())
                    .header("User-Agent", "Mozilla/5.0 (compatible; WechatAgent/1.0)")
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
                return new DownloadedMedia(readLimited(response.body().byteStream(), response.body().contentLength()), contentType);
            } catch (IOException exception) {
                throw new IllegalStateException("媒体下载失败，请重新发送", exception);
            }
        }
        throw new IllegalStateException("媒体下载跳转次数过多");
    }

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

    private StoredMedia requireOwnedActive(String userId, Long mediaId) {
        requireUserId(userId);
        if (mediaId == null || mediaId <= 0) {
            throw new IllegalArgumentException("文件 ID 无效");
        }
        return repository.findByIdAndUserIdAndStatus(mediaId, userId, StoredMedia.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("文件不存在，或不属于当前用户"));
    }

    private Path userRoot(String userId) {
        String readable = userId.replaceAll("[^A-Za-z0-9_-]", "_");
        if (readable.isBlank()) readable = "user";
        if (readable.length() > 48) readable = readable.substring(0, 48);
        return checkedResolve(storageRoot, readable + "-" + sha256(userId.getBytes(StandardCharsets.UTF_8)).substring(0, 12));
    }

    private Path resolveStoredPath(StoredMedia media) {
        Path path = checkedResolve(storageRoot, media.getRelativePath());
        Path ownedRoot = userRoot(media.getUserId());
        if (!path.startsWith(ownedRoot)) {
            throw new IllegalStateException("文件路径不属于当前用户目录");
        }
        return path;
    }

    private Path checkedResolve(Path parent, String child) {
        Path normalizedParent = parent.toAbsolutePath().normalize();
        Path resolved = normalizedParent.resolve(child).normalize();
        if (!resolved.startsWith(normalizedParent)) {
            throw new IllegalArgumentException("非法文件路径");
        }
        return resolved;
    }

    private String availableFileName(Path directory, String requestedName, String extension) {
        String sanitized = sanitizeFileName(requestedName);
        String requestedExtension = explicitExtension(sanitized);
        if (!requestedExtension.isBlank()) {
            sanitized = sanitized.substring(0, sanitized.length() - requestedExtension.length());
        }
        String base = sanitized.isBlank() ? "重要资料" : sanitized;
        if (base.length() > 120) base = base.substring(0, 120);
        String candidate = base + extension;
        int suffix = 2;
        while (Files.exists(checkedResolve(directory, candidate))) {
            candidate = base + "-" + suffix++ + extension;
        }
        return candidate;
    }

    private String baseName(String value) {
        String sanitized = value == null ? "" : value.trim();
        String extension = explicitExtension(sanitized);
        if (!extension.isBlank()) {
            sanitized = sanitized.substring(0, sanitized.length() - extension.length());
        }
        return sanitized.replaceFirst("-\\d+$", "").trim();
    }

    private String formatTime(LocalDateTime value) {
        return value == null ? "未知" : value.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    private String sanitizeFileName(String value) {
        String name = value == null ? "" : value.trim();
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .replaceAll("\\s+", " ")
                .replaceAll("^[. ]+|[. ]+$", "");
        if (name.equals(".") || name.equals("..")) return "重要资料";
        return name;
    }

    private String extensionOf(String fileName, String contentType) {
        String explicit = explicitExtension(fileName);
        if (!explicit.isBlank()) return explicit;
        String mime = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (mime.contains("png")) return ".png";
        if (mime.contains("gif")) return ".gif";
        if (mime.contains("webp")) return ".webp";
        if (mime.contains("jpeg") || mime.contains("jpg")) return ".jpg";
        if (mime.contains("pdf")) return ".pdf";
        if (mime.contains("wordprocessingml")) return ".docx";
        return ".bin";
    }

    private String explicitExtension(String fileName) {
        String name = fileName == null ? "" : fileName;
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String extension = name.substring(dot).toLowerCase(Locale.ROOT);
            if (extension.matches("\\.[a-z0-9]{1,10}")) return extension;
        }
        return "";
    }

    private String normalizeContentType(String primary, String fallback) {
        String value = primary == null || primary.isBlank() ? fallback : primary;
        if (value == null || value.isBlank()) return "application/octet-stream";
        return value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private String searchableText(StoredMedia media) {
        return (blankToEmpty(media.getFileName()) + " " + blankToEmpty(media.getOriginalName()) + " "
                + blankToEmpty(media.getSummary()) + " " + blankToEmpty(media.getImportanceReason()) + " "
                + blankToEmpty(media.getExtractedText())).toLowerCase(Locale.ROOT);
    }

    private void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private String grantKey(String userId, Long mediaId) {
        return userId + ":" + mediaId;
    }

    private void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("当前用户上下文不存在");
        }
    }

    private void requireText(String value, String field, int minimumLength) {
        if (value == null || value.trim().length() < minimumLength) {
            throw new IllegalArgumentException(field + "过短，无法安全执行");
        }
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前环境不支持 SHA-256", exception);
        }
    }
}
