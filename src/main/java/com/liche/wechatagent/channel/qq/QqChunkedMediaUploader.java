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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

final class QqChunkedMediaUploader {
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    QqChunkedMediaUploader(ObjectMapper objectMapper, long timeoutSeconds) {
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .readTimeout(Duration.ofMinutes(10))
                .writeTimeout(Duration.ofMinutes(10))
                .build();
    }

    String upload(RestClient api, String token, String userId, Path file, String fileName, int fileType) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        String md5 = digest(bytes, "MD5");
        String sha1 = digest(bytes, "SHA-1");
        int firstLength = Math.min(bytes.length, 10_002_432);
        String md5_10m = digest(java.util.Arrays.copyOf(bytes, firstLength), "MD5");
        String prepare = api.post().uri("/v2/users/{userId}/upload_prepare", userId)
                .header("Authorization", "QQBot " + token)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(java.util.Map.of(
                        "file_type", fileType, "file_size", String.valueOf(bytes.length),
                        "file_name", fileName, "md5", md5, "sha1", sha1, "md5_10m", md5_10m)))
                .retrieve().body(String.class);
        JsonNode node = objectMapper.readTree(prepare);
        String uploadId = node.path("upload_id").asText("");
        if (uploadId.isBlank()) throw new IOException("QQ 未返回 upload_id");
        JsonNode parts = node.path("parts");
        for (JsonNode part : parts) {
            int index = part.path("index").asInt();
            int offset = index * part.path("block_size").asInt(node.path("block_size").asInt(5 * 1024 * 1024));
            int length = Math.min(part.path("block_size").asInt(5 * 1024 * 1024), bytes.length - offset);
            byte[] chunk = java.util.Arrays.copyOfRange(bytes, offset, offset + length);
            Request request = new Request.Builder().url(part.path("presigned_url").asText())
                    .put(RequestBody.create(chunk, MediaType.parse("application/octet-stream"))).build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) throw new IOException("QQ 分片上传失败: HTTP " + response.code());
            }
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

    private String digest(byte[] bytes, String algorithm) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    }
}
