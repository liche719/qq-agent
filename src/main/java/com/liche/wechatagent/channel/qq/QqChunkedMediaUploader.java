package com.liche.wechatagent.channel.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Consumer;

final class QqChunkedMediaUploader {
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;
    private final Consumer<Throwable> errorReporter;

    QqChunkedMediaUploader(ObjectMapper objectMapper, long timeoutSeconds) {
        this(objectMapper, timeoutSeconds, ignored -> { });
    }

    QqChunkedMediaUploader(ObjectMapper objectMapper, long timeoutSeconds, Consumer<Throwable> errorReporter) {
        this.objectMapper = objectMapper;
        this.errorReporter = errorReporter == null ? ignored -> { } : errorReporter;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .readTimeout(Duration.ofMinutes(10))
                .writeTimeout(Duration.ofMinutes(10))
                .build();
    }

    String upload(RestClient api, String token, String userId, Path file, String fileName, int fileType) throws Exception {
        try {
            return uploadInternal(api, token, userId, file, fileName, fileType);
        } catch (Exception exception) {
            errorReporter.accept(exception);
            throw exception;
        }
    }

    private String uploadInternal(RestClient api, String token, String userId, Path file, String fileName, int fileType) throws Exception {
        long fileSize = Files.size(file);
        String md5 = digestFile(file, "MD5", Long.MAX_VALUE);
        String sha1 = digestFile(file, "SHA-1", Long.MAX_VALUE);
        String md5_10m = digestFile(file, "MD5", 10_002_432L);
        String prepare = api.post().uri("/v2/users/{userId}/upload_prepare", userId)
                .header("Authorization", "QQBot " + token)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(java.util.Map.of(
                        "file_type", fileType, "file_size", String.valueOf(fileSize),
                        "file_name", fileName, "md5", md5, "sha1", sha1, "md5_10m", md5_10m)))
                .retrieve().body(String.class);
        JsonNode node = objectMapper.readTree(prepare);
        String uploadId = node.path("upload_id").asText("");
        if (uploadId.isBlank()) throw new IOException("QQ 未返回 upload_id");
        JsonNode parts = node.path("parts");
        for (JsonNode part : parts) {
            int index = part.path("index").asInt();
            int blockSize = part.path("block_size").asInt(node.path("block_size").asInt(5 * 1024 * 1024));
            long offset = (long) index * blockSize;
            int length = (int) Math.min(blockSize, fileSize - offset);
            byte[] chunk = readChunk(file, offset, length);
            uploadChunkWithRetry(part.path("presigned_url").asText(), chunk, index);
            api.post().uri("/v2/users/{userId}/upload_part_finish", userId)
                    .header("Authorization", "QQBot " + token)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(java.util.Map.of(
                            "upload_id", uploadId, "part_index", index,
                            "block_size", String.valueOf(length), "md5", digest(chunk, "MD5"))))
                    .retrieve().toBodilessEntity();
        }
        String merged = api.post().uri("/v2/users/{userId}/files", userId)
                .header("Authorization", "QQBot " + token)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(java.util.Map.of(
                        "file_type", fileType, "file_name", fileName,
                        "upload_id", uploadId, "srv_send_msg", true)))
                .retrieve().body(String.class);
        return merged == null ? "" : merged;
    }

    private void uploadChunkWithRetry(String url, byte[] chunk, int index) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            Request request = new Request.Builder().url(url)
                    .put(RequestBody.create(chunk, MediaType.parse("application/octet-stream"))).build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) return;
                lastFailure = new IOException("QQ 分片上传失败: HTTP " + response.code());
            } catch (IOException exception) {
                lastFailure = exception;
            }
            if (attempt < 2) {
                try {
                    Thread.sleep(500L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("QQ 分片上传被中断", interrupted);
                }
            }
        }
        throw new IOException("QQ 分片 " + index + " 上传失败（已重试 1 次）", lastFailure);
    }

    private String digest(byte[] bytes, String algorithm) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    }

    private String digestFile(Path file, String algorithm, long maxBytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        byte[] buffer = new byte[8192];
        long remaining = maxBytes;
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while (remaining > 0 && (read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                digest.update(buffer, 0, read);
                remaining -= read;
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private byte[] readChunk(Path file, long offset, int length) throws IOException {
        byte[] chunk = new byte[length];
        try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
            input.seek(offset);
            input.readFully(chunk);
        }
        return chunk;
    }
}
