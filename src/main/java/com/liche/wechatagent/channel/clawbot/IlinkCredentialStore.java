package com.liche.wechatagent.channel.clawbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * iLink 登录凭证持久化（按微信 openid 唯一身份命名文件）：
 * 文件 data/{userId}.json，userId 是微信 openid（botId 每次登录会变，openid 永不变）。
 * 同一个微信账号无论登录多少次、botId 如何变化，凭证文件始终是同一个。
 * 凭证内容含显示名 name、完整 botId/token；恢复时以内容为准。
 */
@Component
public class IlinkCredentialStore {

    private static final Logger log = LoggerFactory.getLogger(IlinkCredentialStore.class);

    public record Credential(String name, String botToken, String userId, String botId, String baseUrl) {

        public static Credential from(String name, LoginContext ctx) {
            return new Credential(name, ctx.getBotToken(), ctx.getUserId(), ctx.getBotId(), ctx.getBaseUrl());
        }

        public LoginContext toLoginContext() {
            return new LoginContext(botToken, userId, botId, baseUrl);
        }
    }

    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final String suffix = ".json";

    public IlinkCredentialStore(ObjectMapper objectMapper,
                                @Value("${wechat.clawbot.token-file:data/ilink-login.json}") String tokenFile) {
        this.objectMapper = objectMapper;
        Path p = Path.of(tokenFile);
        this.dataDir = p.getParent() == null ? Path.of("data") : p.getParent();
    }

    /** 文件名安全化：保留中文/字母/数字/下划线/点/连字符；其余替换为下划线 */
    private String safe(String s) {
        if (s == null || s.isBlank()) {
            return "bot";
        }
        String r = s.replaceAll("[^A-Za-z0-9_.\u4e00-\u9fa5-]", "_");
        return r.isBlank() ? "bot" : r;
    }

    private Path fileForUserId(String userId) {
        return dataDir.resolve(safe(userId) + suffix);
    }

    /** 保存：文件名 = 微信 openid（userId，永不变化的身份） */
    public synchronized void save(String name, LoginContext ctx) {
        try {
            Files.createDirectories(dataDir);
            Path f = fileForUserId(ctx.getUserId());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(f.toFile(), Credential.from(name, ctx));
            log.info("iLink 登录凭证已保存: {} (name={}, botId={})", f.toAbsolutePath(), name, ctx.getBotId());
        } catch (IOException e) {
            log.warn("保存 iLink 登录凭证失败 name={}", name, e);
        }
    }

    /** 按 openid 加载凭证 */
    public synchronized Credential loadByUserId(String userId) {
        Path f = fileForUserId(userId);
        if (!Files.exists(f)) {
            return null;
        }
        try {
            Credential c = objectMapper.readValue(f.toFile(), Credential.class);
            if (c.botToken() == null || c.botToken().isBlank()) {
                return null;
            }
            if (c.userId() != null && !c.userId().isBlank() && safe(c.userId()).equals(safe(userId))) {
                return c;
            }
            log.warn("凭证 openid 与文件名不一致，忽略: {}", f.getFileName());
            return null;
        } catch (IOException e) {
            log.warn("读取 iLink 登录凭证失败 userId={}（可能损坏，需要重新扫码）", userId, e);
            return null;
        }
    }

    /** 列出全部凭证（以内容为准） */
    public synchronized List<Credential> listAll() {
        List<Credential> result = new ArrayList<>();
        if (!Files.isDirectory(dataDir)) {
            return result;
        }
        try (var stream = Files.list(dataDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(suffix))
                    .forEach(f -> {
                        try {
                            Credential c = objectMapper.readValue(f.toFile(), Credential.class);
                            if (c.botToken() != null && !c.botToken().isBlank()) {
                                result.add(c);
                            }
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException e) {
            log.warn("列出 iLink 凭证失败", e);
        }
        return result;
    }

    /** 按 openid 清除凭证 */
    public synchronized void clearByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(fileForUserId(userId));
            log.info("已清除 iLink 登录凭证: {}", userId);
        } catch (IOException e) {
            log.warn("清除 iLink 登录凭证失败 userId={}", userId, e);
        }
    }

    /** 按机器人名字清除凭证（遍历所有凭证文件，删除 name 匹配的）——退出登录的兜底清理 */
    public synchronized void clearByName(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        for (Credential c : listAll()) {
            if (name.equals(c.name())) {
                clearByUserId(c.userId());
            }
        }
        // 也兼容按名字命名的旧格式文件（如 data/我的.json）
        try {
            Files.deleteIfExists(dataDir.resolve(safe(name) + suffix));
            log.info("已清除 iLink 登录凭证(按名): {}", name);
        } catch (IOException e) {
            log.warn("清除 iLink 登录凭证失败 name={}", name, e);
        }
    }
}
