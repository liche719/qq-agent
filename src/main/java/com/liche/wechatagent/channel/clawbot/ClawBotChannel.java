package com.liche.wechatagent.channel.clawbot;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.liche.wechatagent.agent.AgentOrchestrator;
import com.liche.wechatagent.channel.InboundMessage;
import com.liche.wechatagent.channel.WeChatChannel;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 真实微信通道（扫码即用，无需名字）：
 * - 身份 = 微信 openid（唯一且不变）；凭证文件 data/{openid}.json
 * - 点「扫码登录」→ 后端生成二维码（无名字）→ 手机微信扫码 → 登录成功以 openid 注册
 * - 退出登录 → 删除该 openid 凭证文件；再次扫码 → 重新生成（仍以 openid 为文件名）
 * - 入站消息带 botId，回复通过对应 bot 发送；SDK 内置心跳长轮询
 */
@Component
@ConditionalOnProperty(name = "wechat.channel.mode", havingValue = "clawbot")
public class ClawBotChannel implements WeChatChannel {

    private static final Logger log = LoggerFactory.getLogger(ClawBotChannel.class);

    public static class BotSession {
        /** 显示标识（登录后 = openid；扫码中 = 临时 loginId） */
        public volatile String name;
        public volatile ILinkClient client;
        public volatile String botId;
        public volatile String userId;
        public volatile String qrCodeUrl;

        public BotSession(String name) {
            this.name = name;
        }

        public boolean loggedIn() {
            ILinkClient c = client;
            return c != null && c.isLoggedIn();
        }
    }

    private final IlinkCredentialStore credentialStore;
    private final AgentOrchestrator orchestrator;
    private final long heartbeatIntervalMs;

    /** 已登录机器人：key = openid */
    private final Map<String, BotSession> bots = new ConcurrentHashMap<>();
    /** 扫码中的临时登录：key = loginId（前端轮询用） */
    private final Map<String, BotSession> pendingLogins = new ConcurrentHashMap<>();

    public ClawBotChannel(IlinkCredentialStore credentialStore,
                          @Lazy AgentOrchestrator orchestrator,
                          @Value("${wechat.clawbot.heartbeat-interval-ms:3000}") long heartbeatIntervalMs) {
        this.credentialStore = credentialStore;
        this.orchestrator = orchestrator;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
    }

    /** 应用就绪后恢复所有已登录机器人（按 openid 凭证） */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        for (IlinkCredentialStore.Credential c : credentialStore.listAll()) {
            try {
                BotSession session = new BotSession(c.userId());
                session.userId = c.userId();
                session.botId = c.botId();
                session.client = buildClient(session, c.toLoginContext());
                bots.put(c.userId(), session);
                log.info("恢复 iLink 登录态: {} (botId={})", c.userId(), c.botId());
            } catch (Exception e) {
                log.warn("恢复机器人失败 userId={}，跳过", c.userId(), e);
            }
        }
        if (bots.isEmpty()) {
            log.info("暂无 iLink 机器人，请在管理页点「扫码登录」");
        }
    }

    @PreDestroy
    public void shutdown() {
        for (BotSession s : bots.values()) {
            closeQuietly(s);
        }
        for (BotSession s : pendingLogins.values()) {
            closeQuietly(s);
        }
    }

    private void closeQuietly(BotSession s) {
        ILinkClient c = s.client;
        if (c != null) {
            try {
                c.close();
            } catch (Exception e) {
                log.warn("关闭 iLink 客户端失败", e);
            }
        }
    }

    // ---------- WeChatChannel ----------

    @Override
    public String channel() {
        return "wechat";
    }

    @Override
    public void sendText(String userId, String text) {
        sendTextFrom(null, userId, text);
    }

    @Override
    public void sendTextFrom(String botId, String userId, String text) {
        BotSession session = findSession(botId);
        if (session == null) {
            log.warn("找不到对应机器人(botId={})，无法发送给 {}", botId, userId);
            return;
        }
        ILinkClient c = session.client;
        if (c == null || !c.isLoggedIn()) {
            log.warn("机器人未登录，无法发送给 {}", userId);
            return;
        }
        try {
            c.sendText(userId, text);
            try {
                c.stopTyping(userId);
            } catch (Exception ignored) {
            }
            log.info("[ilink:{}] 发送 -> {} ({} chars)", session.name, userId, text == null ? 0 : text.length());
        } catch (Exception e) {
            log.warn("[ilink:{}] 发送失败 userId={}", session.name, userId, e);
        }
    }

    // ---------- 扫码登录（无名字） ----------

    /** 生成登录二维码（无需名字）；返回 loginId 供前端轮询扫码结果 */
    public synchronized Map<String, String> register() {
        String loginId = UUID.randomUUID().toString().substring(0, 8);
        BotSession session = new BotSession(loginId);
        session.client = buildClient(session, null);
        pendingLogins.put(loginId, session);
        try {
            String qr = session.client.executeLogin();
            session.qrCodeUrl = qr;
            return Map.of("loginId", loginId, "qrcodeUrl", qr);
        } catch (Exception e) {
            pendingLogins.remove(loginId);
            log.warn("获取 iLink 二维码失败", e);
            throw new IllegalStateException("获取二维码失败：" + e.getMessage());
        }
    }

    public List<BotSession> sessions() {
        return new ArrayList<>(bots.values());
    }

    /** 根据 openid 找到负责给该用户发消息的 bot（记忆确认/提醒推送用） */
    public String botIdForUser(String userId) {
        if (userId == null) {
            return null;
        }
        BotSession s = bots.get(userId);
        return s == null ? null : s.botId;
    }

    public synchronized void logout(String openid) {
        BotSession session = bots.remove(openid);
        if (session != null) {
            closeQuietly(session);
        }
        credentialStore.clearByUserId(openid);
        log.info("已退出 iLink 机器人: {}", openid);
    }

    // ---------- 内部 ----------

    private BotSession findSession(String botId) {
        if (botId == null) {
            return bots.values().stream().findFirst().orElse(null);
        }
        for (BotSession s : bots.values()) {
            if (botId.equals(s.botId)) {
                return s;
            }
        }
        return null;
    }

    private ILinkClient buildClient(BotSession session, LoginContext resume) {
        ILinkConfig config = ILinkConfig.builder()
                .heartbeatIntervalMs(heartbeatIntervalMs)
                .build();
        var builder = ILinkClient.builder()
                .config(config)
                .onHeartbeat(new com.github.wechat.ilink.sdk.core.listener.OnHeartbeatListener() {
                    private volatile boolean degraded;

                    @Override
                    public void onHeartbeatSuccess() {
                        log.debug("[ilink:{}] getupdates 心跳正常", session.name);
                    }

                    @Override
                    public void onHeartbeatFailure(Throwable t) {
                        String msg = t == null ? "" : String.valueOf(t.getMessage());
                        // iLink 会话过期（ret=-14）：旧会话与新登录冲突导致，无法自动恢复 → 降级并提示重新扫码
                        if (msg.contains("session expired") || t instanceof com.github.wechat.ilink.sdk.core.exception.SessionExpiredException) {
                            if (degraded) {
                                return; // 已处理过，避免重复清理
                            }
                            degraded = true;
                            log.warn("[ilink:{}] iLink 会话过期，停止该机器人并清除凭证，请重新扫码登录", session.name);
                            ILinkClient c = session.client;
                            if (c != null) {
                                try {
                                    c.close();
                                } catch (Exception ignored) {
                                }
                            }
                            session.client = null;
                            if (session.userId != null) {
                                bots.remove(session.userId);
                                credentialStore.clearByUserId(session.userId);
                            }
                            return;
                        }
                        log.warn("[ilink:{}] getupdates 心跳失败: {}", session.name, msg);
                    }
                })
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext ctx) {
                        String openid = ctx.getUserId();
                        // 从扫码中的临时会话迁移为正式（key = openid）
                        pendingLogins.remove(session.name);
                        session.name = openid;
                        session.userId = openid;
                        session.botId = ctx.getBotId();
                        session.qrCodeUrl = null;
                        bots.put(openid, session);
                        log.info("iLink 登录成功 openid={} botId={}", openid, ctx.getBotId());
                        credentialStore.save(openid, ctx); // 凭证文件 = {openid}.json
                    }

                    @Override
                    public void onLoginFailure(Throwable t) {
                        log.warn("iLink 登录失败 loginId={}: {}", session.name, t.getMessage());
                    }
                })
                .onMessage(new OnMessageListener() {
                    @Override
                    public void onMessages(List<WeixinMessage> messages) {
                        for (WeixinMessage m : messages) {
                            dispatch(session, m);
                        }
                    }
                });
        if (resume != null) {
            builder.loginContext(resume);
        }
        return builder.build();
    }

    /** 入站消息：提取文本 → InboundMessage(botId) → AgentOrchestrator */
    private void dispatch(BotSession session, WeixinMessage m) {
        if (m.getFrom_user_id() == null || m.getMessage_id() == null) {
            return;
        }
        String text = extractText(m);
        if (text == null) {
            sendTextFrom(session.botId, m.getFrom_user_id(), "我目前只能看懂文字消息哦，图片、语音、文件暂时还不行～");
            return;
        }
        InboundMessage inbound = InboundMessage.text(
                String.valueOf(m.getMessage_id()),
                m.getFrom_user_id(),
                text,
                session.botId,
                "wechat");
        try {
            orchestrator.onInbound(inbound);
        } catch (Exception e) {
            log.warn("消息入管道失败 userId={}", m.getFrom_user_id(), e);
        }
    }

    private String extractText(WeixinMessage m) {
        if (m.getItem_list() == null) {
            return null;
        }
        for (MessageItem item : m.getItem_list()) {
            if (item.getText_item() != null && item.getText_item().getText() != null
                    && !item.getText_item().getText().isBlank()) {
                return item.getText_item().getText();
            }
        }
        return null;
    }
}
