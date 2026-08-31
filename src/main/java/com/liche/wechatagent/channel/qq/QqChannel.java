package com.liche.wechatagent.channel.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.liche.wechatagent.agent.AgentOrchestrator;
import com.liche.wechatagent.agent.StreamReplySink;
import com.liche.wechatagent.channel.InboundMessage;
import com.liche.wechatagent.channel.InboundAttachment;
import com.liche.wechatagent.channel.WeChatChannel;
import com.liche.wechatagent.channel.OutboundMedia;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(name = "qq.enabled", havingValue = "true")
public class QqChannel implements WeChatChannel {

    private static final Logger log = LoggerFactory.getLogger(QqChannel.class);
    private static final String TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";
    private static final String API_BASE = "https://api.sgroup.qq.com";
    private static final String SANDBOX_API_BASE = "https://sandbox.api.sgroup.qq.com";
    private static final String GROUP_CONVERSATION_PREFIX = "qq-group:";
    private static final int INTENT_FULL = (1 << 0) | (1 << 1) | (1 << 30) | (1 << 12) | (1 << 25) | (1 << 26);
    /** 被动消息 msg_id 有效窗口 5 分钟；超过则降级主动消息 */
    private static final long PASSIVE_WINDOW_MS = 5 * 60 * 1000L;
    /** 输入状态续期间隔（QQ 输入窗口约 60s） */
    private static final long TYPING_KEEPALIVE_MS = 50_000L;
    private static final long EPHEMERAL_CACHE_TTL_MS = 30 * 60 * 1000L;
    private static final long STREAM_STATE_TTL_MS = 10 * 60 * 1000L;
    private static final int MAX_CONVERSATION_CACHE_ENTRIES = 2_048;
    private static final int MAX_MESSAGE_CACHE_ENTRIES = 4_096;

    private final String appId;
    private final String clientSecret;
    private final String apiBase;
    private final boolean groupEnabled;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentOrchestrator orchestrator;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final QqMessageSequence messageSequence = new QqMessageSequence();

    private volatile String accessToken;
    private volatile long tokenExpireAtMs;
    private volatile String selfOpenid; // 机器人自己的 openid（从 READY 记录，用于过滤 bot 消息回显）
    private volatile String sessionId;   // 网关会话 id（断线后 RESUME 恢复用）
    private volatile int lastSeq = 0;    // 最后收到的消息序列号（RESUME 用）
    private volatile WebSocket ws;
    private volatile ScheduledExecutorService heartbeatExecutor;
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile ScheduledExecutorService typingExecutor;
    /** userId -> 最近收到消息的时间戳（用于判断被动窗口） */
    private final ConcurrentMap<String, Long> lastRecvAt = new ConcurrentHashMap<>();
    /** userId -> 最近消息 id（被动回复用） */
    private final ConcurrentMap<String, String> lastMsgIds = new ConcurrentHashMap<>();
    /** conversationId + msgId -> 接收时间，用于指定消息的被动回复窗口判断。 */
    private final ConcurrentMap<String, Long> receivedAtByMessage = new ConcurrentHashMap<>();
    /** userId -> 输入状态续期任务 */
    private final ConcurrentMap<String, ScheduledFuture<?>> typingTasks = new ConcurrentHashMap<>();
    /** Only short-lived, per-user copies of received messages, used when QQ quote lookup is temporarily unavailable. */
    private final ConcurrentMap<String, ReceivedQuote> receivedMessages = new ConcurrentHashMap<>();

    private record ReceivedQuote(QqQuoteMessage message, long expiresAt) {
    }

    @Autowired
    public QqChannel(@Value("${qq.app-id}") String appId,
                     @Value("${qq.client-secret}") String clientSecret,
                     @Value("${qq.sandbox:false}") boolean sandbox,
                     @Value("${qq.group-enabled:false}") boolean groupEnabled,
                     @Lazy AgentOrchestrator orchestrator) {
        this.appId = appId;
        this.clientSecret = clientSecret;
        this.apiBase = sandbox ? SANDBOX_API_BASE : API_BASE;
        this.groupEnabled = groupEnabled;
        this.orchestrator = orchestrator;
    }

    QqChannel(String appId, String clientSecret, boolean sandbox, AgentOrchestrator orchestrator) {
        this(appId, clientSecret, sandbox, false, orchestrator);
    }

    @Override
    public String channel() { return "qq"; }

    @EventListener(ApplicationReadyEvent.class)
    public void start() { new Thread(this::connectLoop, "qq-connect").start(); }

    private void connectLoop() {
        long[] backoff = {2000, 5000, 10000, 30000};
        int attempt = 0;
        while (running.get()) {
            try {
                ensureToken();
                String wsUrl = fetchGatewayUrl();
                log.info("QQ bot connecting: {}", wsUrl);
                connectWebSocket(wsUrl);
                attempt = 0;
                return;
            } catch (Exception e) {
                log.warn("QQ bot connect failed: {}", e.getMessage());
                long delay = backoff[Math.min(attempt, backoff.length - 1)];
                attempt++;
                try { Thread.sleep(delay); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private void connectWebSocket(String wsUrl) throws Exception {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ZERO)
                .build();
        Request request = new Request.Builder()
                .url(wsUrl)
                .header("Authorization", "QQBot " + accessToken)
                .header("User-Agent", "qqbot-nodejs/1.0.4")
                .build();
        this.ws = client.newWebSocket(request, new QqWebSocketListener());
        log.info("QQ bot WebSocket connected");
    }

    private void reconnect() {
        if (!running.get()) return;
        stopHeartbeat();
        try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        new Thread(this::connectLoop, "qq-reconnect").start();
    }

    private synchronized void ensureToken() {
        if (accessToken != null && System.currentTimeMillis() < tokenExpireAtMs - 60_000) return;
        try {
            RestClient client = buildRestClient(TOKEN_URL, 10);
            ObjectNode body = objectMapper.createObjectNode();
            body.put("appId", appId);
            body.put("clientSecret", clientSecret);
            String resp = client.post().uri("").contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString()).retrieve().body(String.class);
            JsonNode node = objectMapper.readTree(resp);
            accessToken = node.path("access_token").asText("");
            int expiresIn = node.path("expires_in").asInt(7200);
            tokenExpireAtMs = System.currentTimeMillis() + expiresIn * 1000L;
            log.info("QQ access_token ok, expires {}s", expiresIn);
        } catch (Exception e) {
            throw new RuntimeException("QQ access_token failed: " + e.getMessage(), e);
        }
    }

    private String fetchGatewayUrl() {
        RestClient client = buildRestClient(apiBase, 15);
        String resp = client.get().uri("/gateway")
                .header("Authorization", "QQBot " + accessToken)
                .retrieve().body(String.class);
        try {
            JsonNode node = objectMapper.readTree(resp);
            return node.path("url").asText("");
        } catch (Exception e) {
            throw new RuntimeException("QQ gateway 解析失败", e);
        }
    }

    private RestClient buildRestClient(String baseUrl, int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    private class QqWebSocketListener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            log.info("QQ WebSocket onOpen");
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            handleFrame(text, webSocket);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            log.warn("QQ WebSocket closed: code={} reason={}", code, reason);
            if (code == 4004) {
                log.error("QQ 鉴权失败：请检查 QQ 开放平台 IP 白名单（当前出口 IP）与 AppID/AppSecret");
                running.set(false);
                return;
            }
            reconnect();
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            log.warn("QQ WebSocket failure: {}", t.getMessage());
            reconnect();
        }
    }

    private void sendIdentify(WebSocket webSocket) {
        try {
            ObjectNode identify = objectMapper.createObjectNode();
            identify.put("op", 2);
            ObjectNode d = identify.putObject("d");
            d.put("token", "QQBot " + accessToken);
            d.put("intents", INTENT_FULL);
            var shard = d.putArray("shard");
            shard.add(0); shard.add(1);
            webSocket.send(identify.toString());
            log.info("QQ Identify sent (FULL_INTENTS)");
        } catch (Exception e) {
            log.warn("QQ Identify failed: {}", e.getMessage());
        }
    }

    /** RESUME 恢复会话：断线重连后用 session_id + lastSeq 恢复，避免消息路由切换延迟 */
    private void sendResume(WebSocket webSocket) {
        try {
            ObjectNode resume = objectMapper.createObjectNode();
            resume.put("op", 6);
            ObjectNode d = resume.putObject("d");
            d.put("token", "QQBot " + accessToken);
            d.put("session_id", sessionId);
            d.put("seq", lastSeq);
            webSocket.send(resume.toString());
            log.info("QQ RESUME sent session={} seq={}", sessionId, lastSeq);
        } catch (Exception e) {
            log.warn("QQ RESUME failed: {}", e.getMessage());
        }
    }

    private void handleFrame(String text, WebSocket webSocket) {
        try {
            JsonNode node = objectMapper.readTree(text);
            int op = node.path("op").asInt(-1);
            if (op == 11) {
                // 心跳 ack，静默
            } else if (op == 10) {
                // 有会话可恢复则 RESUME，否则全新 IDENTIFY
                if (sessionId != null && lastSeq > 0) {
                    sendResume(webSocket);
                } else {
                    sendIdentify(webSocket);
                }
                int interval = node.path("d").path("heartbeat_interval").asInt(45000);
                startHeartbeat(interval);
            } else if (op == 7) {
                // 服务器要求重连：立即重连
                log.info("QQ gateway RECONNECT requested, reconnecting");
                reconnect();
            } else if (op == 9) {
                // 会话失效：无法恢复，清空 session 状态重新 IDENTIFY
                boolean canResume = node.path("d").asBoolean(false);
                if (!canResume) {
                    sessionId = null;
                    lastSeq = 0;
                }
                log.info("QQ gateway INVALID_SESSION, reconnecting");
                reconnect();
            } else if (op == 0) {
                int s = node.path("s").asInt(0);
                if (s > 0) lastSeq = s;
                String t = node.path("t").asText("");
                if ("READY".equals(t)) {
                    selfOpenid = node.path("d").path("user").path("id").asText("");
                    sessionId = node.path("d").path("session_id").asText("");
                    log.info("QQ gateway READY: session established, botId={}", selfOpenid);
                } else if ("C2C_MESSAGE_CREATE".equals(t)) {
                    JsonNode d = node.path("d");
                    String openid = d.path("author").path("id").asText("");
                    String msgId = d.path("id").asText("");
                    String content = d.path("content").asText("");
                    // 过滤机器人自己的消息（主动/流式/确认消息回显），否则会把 bot 自己的话当用户输入
                    boolean isBotMsg = d.path("author").path("bot").asBoolean(false);
                    if (!isBotMsg && selfOpenid != null && selfOpenid.equals(openid)) {
                        isBotMsg = true;
                    }
                    if (isBotMsg) {
                        log.info("[qq] ignore bot-self msg -> {}", openid);
                    } else if (!openid.isBlank()) {
                        // 图片消息：attachments 携带图片 URL，content 可能为空
                        List<String> images = new java.util.ArrayList<>();
                        List<InboundAttachment> attachments = new java.util.ArrayList<>();
                        for (JsonNode att : d.path("attachments")) {
                            String url = att.path("url").asText("");
                            String contentType = att.path("content_type").asText("");
                            String name = att.path("filename").asText("");
                            if (url.isBlank()) continue;
                            if (contentType.startsWith("image/")) {
                                images.add(url);
                            } else {
                                attachments.add(new InboundAttachment(name, contentType, url));
                            }
                        }
                        if (images.isEmpty() && attachments.isEmpty() && content.isBlank()) {
                            log.info("[qq] recv (empty, no attachments) -> {}", openid);
                        } else {
                            rememberReplyWindow(openid, msgId);
                            startTyping(openid); // 通知用户「正在输入」
                            rememberReceivedMessage(openid, msgId, content, images, attachments);
                            log.info("[qq] inbound metadata user={} msg={} fields={}", openid, msgId, fieldNames(d));
                            log.info("[qq] inbound element schema user={} msg={} schema={}", openid, msgId,
                                    describeJsonShape(d.path("msg_elements"), 0));
                            QqQuoteMessage quote = resolveQuote(openid, d);
                            InboundMessage inbound;
                            if (images.isEmpty() && attachments.isEmpty() && quote.content().isBlank() && quote.imageUrls().isEmpty() && quote.attachments().isEmpty()) {
                                inbound = InboundMessage.text(msgId, openid, content, "qq", "qq");
                                log.info("[qq] recv -> {} ({} chars)", openid, content.length());
                            } else {
                                inbound = InboundMessage.textWithQuote(msgId, openid, content, "qq", "qq", images, attachments,
                                        quote.content(), quote.imageUrls(), quote.attachments());
                                log.info("[qq] recv(image x{}, file x{}, quote={} chars/{} images) -> {}", images.size(), attachments.size(),
                                        quote.content().length(), quote.imageUrls().size(), openid);
                            }
                            orchestrator.onInbound(inbound);
                        }
                    }
                } else if ("GROUP_AT_MESSAGE_CREATE".equals(t) && groupEnabled) {
                    JsonNode d = node.path("d");
                    String groupOpenid = d.path("group_openid").asText("");
                    String memberOpenid = d.path("author").path("member_openid").asText("");
                    String msgId = d.path("id").asText("");
                    String content = d.path("content").asText("");
                    boolean isBotMsg = d.path("author").path("bot").asBoolean(false);
                    if (!isBotMsg && selfOpenid != null && selfOpenid.equals(memberOpenid)) {
                        isBotMsg = true;
                    }
                    if (isBotMsg) {
                        log.info("[qq] ignore bot-self group msg -> group={}", groupOpenid);
                    } else if (!groupOpenid.isBlank() && !memberOpenid.isBlank()) {
                        List<String> images = new java.util.ArrayList<>();
                        List<InboundAttachment> attachments = new java.util.ArrayList<>();
                        for (JsonNode att : d.path("attachments")) {
                            String url = att.path("url").asText("");
                            String contentType = att.path("content_type").asText("");
                            String name = att.path("filename").asText("");
                            if (url.isBlank()) continue;
                            if (contentType.startsWith("image/")) {
                                images.add(url);
                            } else {
                                attachments.add(new InboundAttachment(name, contentType, url));
                            }
                        }
                        if (images.isEmpty() && attachments.isEmpty() && content.isBlank()) {
                            log.info("[qq] recv group (empty, no attachments) -> {}", groupOpenid);
                        } else {
                            String conversationId = groupConversationId(groupOpenid);
                            String memberContent = "【群成员 " + memberOpenid + "】" + (content.isBlank() ? "[图片]" : content);
                            rememberReplyWindow(conversationId, msgId);
                            InboundMessage inbound = images.isEmpty() && attachments.isEmpty()
                                    ? InboundMessage.text(msgId, conversationId, memberContent, "qq", "qq")
                                    : InboundMessage.textWithAttachments(msgId, conversationId, memberContent, "qq", "qq", images, attachments);
                            log.info("[qq] recv group={} member={} ({} chars, image x{}, file x{})", groupOpenid, memberOpenid,
                                    content.length(), images.size(), attachments.size());
                            orchestrator.onInbound(inbound);
                        }
                    }
                } else {
                    log.info("QQ event: t={}", t);
                }
            }
        } catch (Exception e) {
            log.warn("QQ frame parse failed", e);
        }
    }

    private void rememberReceivedMessage(String userId, String messageId, String content, List<String> images,
                                         List<InboundAttachment> attachments) {
        if (messageId == null || messageId.isBlank()) return;
        long now = System.currentTimeMillis();
        receivedMessages.put(quoteCacheKey(userId, messageId), new ReceivedQuote(new QqQuoteMessage(messageId,
                content == null ? "" : content, images == null ? List.of() : List.copyOf(images),
                attachments == null ? List.of() : List.copyOf(attachments)), now + EPHEMERAL_CACHE_TTL_MS));
        pruneEphemeralCaches(now);
    }

    private QqQuoteMessage resolveQuote(String userId, JsonNode event) {
        QqQuoteMessage embedded = QqQuoteMessage.fromEvent(event);
        if (!embedded.content().isBlank() || !embedded.imageUrls().isEmpty() || !embedded.attachments().isEmpty()) return embedded;
        if (embedded.messageId().isBlank()) return embedded;
        String cacheKey = quoteCacheKey(userId, embedded.messageId());
        ReceivedQuote cached = receivedMessages.get(cacheKey);
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) return cached.message();
        if (cached != null) receivedMessages.remove(cacheKey, cached);
        try {
            RestClient client = buildRestClient(apiBase, 10);
            String response = client.get().uri("/v2/users/{openid}/messages/{messageId}", userId, embedded.messageId())
                    .header("Authorization", "QQBot " + accessToken).retrieve().body(String.class);
            QqQuoteMessage fetched = QqQuoteMessage.fromLookup(objectMapper.readTree(response), embedded.messageId());
            if (!fetched.content().isBlank() || !fetched.imageUrls().isEmpty() || !fetched.attachments().isEmpty()) {
                rememberReceivedMessage(userId, fetched.messageId(), fetched.content(), fetched.imageUrls(), fetched.attachments());
                return fetched;
            }
        } catch (Exception exception) {
            log.info("[qq] quote lookup unavailable user={} msg={}: {}", userId, embedded.messageId(), exception.getMessage());
        }
        return embedded;
    }

    private String quoteCacheKey(String userId, String messageId) {
        return userId + '\u0000' + messageId;
    }

    private void rememberReplyWindow(String conversationId, String messageId) {
        if (conversationId == null || conversationId.isBlank() || messageId == null || messageId.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        lastMsgIds.put(conversationId, messageId);
        lastRecvAt.put(conversationId, now);
        receivedAtByMessage.put(replyCacheKey(conversationId, messageId), now);
        pruneEphemeralCaches(now);
    }

    private void pruneEphemeralCaches(long now) {
        lastRecvAt.entrySet().removeIf(entry -> now - entry.getValue() >= EPHEMERAL_CACHE_TTL_MS);
        lastMsgIds.keySet().removeIf(conversationId -> !lastRecvAt.containsKey(conversationId));
        receivedAtByMessage.entrySet().removeIf(entry -> now - entry.getValue() >= EPHEMERAL_CACHE_TTL_MS);
        receivedMessages.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        typingTasks.entrySet().removeIf(entry -> entry.getValue().isCancelled() || entry.getValue().isDone());
        streamStates.entrySet().removeIf(entry -> now - entry.getValue().createdAtMillis >= STREAM_STATE_TTL_MS);
        trimConversationCache();
        trimMessageCache();
        trimQuoteCache();
    }

    private void trimConversationCache() {
        int overflow = lastRecvAt.size() - MAX_CONVERSATION_CACHE_ENTRIES;
        if (overflow <= 0) {
            return;
        }
        lastRecvAt.entrySet().stream()
                .sorted(Comparator.comparingLong(Map.Entry::getValue))
                .limit(overflow)
                .forEach(entry -> {
                    if (lastRecvAt.remove(entry.getKey(), entry.getValue())) {
                        lastMsgIds.remove(entry.getKey());
                        stopTyping(entry.getKey());
                    }
                });
    }

    private void trimMessageCache() {
        int overflow = receivedAtByMessage.size() - MAX_MESSAGE_CACHE_ENTRIES;
        if (overflow <= 0) {
            return;
        }
        receivedAtByMessage.entrySet().stream()
                .sorted(Comparator.comparingLong(Map.Entry::getValue))
                .limit(overflow)
                .forEach(entry -> receivedAtByMessage.remove(entry.getKey(), entry.getValue()));
    }

    private void trimQuoteCache() {
        int overflow = receivedMessages.size() - MAX_MESSAGE_CACHE_ENTRIES;
        if (overflow <= 0) {
            return;
        }
        receivedMessages.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().expiresAt()))
                .limit(overflow)
                .forEach(entry -> receivedMessages.remove(entry.getKey(), entry.getValue()));
    }

    private List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** Diagnostics for QQ rich-message compatibility: records only JSON types and field names, never message values. */
    private String describeJsonShape(JsonNode node, int depth) {
        if (node == null || node.isMissingNode() || node.isNull()) return "null";
        if (depth >= 4) return node.isArray() ? "[…]" : node.isObject() ? "{…}" : node.getNodeType().name();
        if (node.isArray()) {
            if (node.isEmpty()) return "[]";
            return "[" + describeJsonShape(node.get(0), depth + 1) + "]";
        }
        if (!node.isObject()) return node.getNodeType().name();
        List<String> parts = new java.util.ArrayList<>();
        node.fields().forEachRemaining(entry -> parts.add(entry.getKey() + ":" + describeJsonShape(entry.getValue(), depth + 1)));
        return "{" + String.join(",", parts) + "}";
    }

    private String replyCacheKey(String conversationId, String messageId) {
        return conversationId + ':' + messageId;
    }

    // ============ 「正在输入」状态（C2C 长回复体验） ============

    private void startTyping(String userId) {
        stopTyping(userId);
        if (typingExecutor == null) {
            typingExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "qq-typing"); t.setDaemon(true); return t;
            });
        }
        // 立即发一次（异步，不阻塞 WS 线程），之后每 50s 续期
        typingExecutor.execute(() -> sendInputNotifyQuietly(userId));
        ScheduledFuture<?> task = typingExecutor.scheduleAtFixedRate(
                () -> sendInputNotifyQuietly(userId),
                TYPING_KEEPALIVE_MS, TYPING_KEEPALIVE_MS, TimeUnit.MILLISECONDS);
        typingTasks.put(userId, task);
    }

    private void stopTyping(String userId) {
        ScheduledFuture<?> task = typingTasks.remove(userId);
        if (task != null) task.cancel(true);
    }

    private void sendInputNotifyQuietly(String userId) {
        try {
            ensureToken();
            RestClient client = buildRestClient(apiBase, 10);
            ObjectNode body = objectMapper.createObjectNode();
            body.put("msg_type", 6);
            ObjectNode notify = body.putObject("input_notify");
            notify.put("input_type", 1);
            notify.put("input_second", 60);
            body.put("msg_seq", messageSequence.next());
            client.post().uri("/v2/users/{openid}/messages", userId)
                    .header("Authorization", "QQBot " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve().body(String.class);
        } catch (Exception ignored) {
            // 输入状态失败可忽略
        }
    }

    // ============ 心跳 ============

    private void startHeartbeat(int intervalMs) {
        stopHeartbeat();
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "qq-heartbeat"); t.setDaemon(true); return t;
        });
        heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(() -> {
            WebSocket s = ws;
            if (s != null) {
                try { ObjectNode hb = objectMapper.createObjectNode(); hb.put("op", 1); hb.putNull("d"); s.send(hb.toString()); }
                catch (Exception ignored) { }
            }
        }, intervalMs / 2, intervalMs / 2, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatTask != null) { heartbeatTask.cancel(true); heartbeatTask = null; }
        if (heartbeatExecutor != null) { heartbeatExecutor.shutdownNow(); heartbeatExecutor = null; }
    }

    @Override
    public String botIdForUser(String userId) {
        pruneEphemeralCaches(System.currentTimeMillis());
        return lastMsgIds.containsKey(userId) ? "qq" : null;
    }

    @Override
    public void sendText(String userId, String text) { sendTextFrom(null, userId, text); }

    @Override
    public void sendTextFrom(String botId, String userId, String text) {
        sendTextReplyFrom(botId, userId, null, text);
    }

    @Override
    public void sendTextReplyFrom(String botId, String userId, String replyToMsgId, String text) {
        try {
            sendWithPassiveFirst(userId, replyToMsgId, text);
        } finally {
            if (!isGroupConversation(userId)) {
                stopTyping(userId);
            }
            // 清理未使用的流式会话（确认流程等未走 agent 的回复不会触发 onDone）
            streamStates.remove(userId);
        }
    }

    @Override
    public boolean sendMediaReplyFrom(String botId, String userId, String replyToMsgId, OutboundMedia media) {
        if (isGroupConversation(userId) || media == null || media.localFile() == null || !Files.isRegularFile(media.localFile())) {
            return false;
        }
        try {
            ensureToken();
            ObjectNode body = objectMapper.createObjectNode();
            body.put("file_type", qqFileType(media.contentType()));
            body.put("file_data", Base64.getEncoder().encodeToString(Files.readAllBytes(media.localFile())));
            body.put("file_name", media.fileName());
            body.put("srv_send_msg", true);
            RestClient client = buildRestClient(apiBase, 30);
            client.post().uri("/v2/users/{openid}/files", userId)
                    .header("Authorization", "QQBot " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve().body(String.class);
            log.info("[qq] uploaded local media user={} file={}", userId, media.fileName());
            return true;
        } catch (Exception exception) {
            log.warn("[qq] media send failed user={}: {}", userId, exception.getMessage());
            return false;
        }
    }

    private int qqFileType(String contentType) {
        String normalized = contentType == null ? "" : contentType.toLowerCase();
        if (normalized.startsWith("image/png") || normalized.startsWith("image/jpeg")) return 1;
        if (normalized.startsWith("video/mp4")) return 2;
        if (normalized.contains("silk")) return 3;
        return 4;
    }

    /**
     * 优先被动回复（带 msg_id，5 分钟窗口内有效）；失败则降级主动消息。
     * 被动消息每用户每天 1000 条上限且未认证频控 5/qp、30/qpm —— 主动仅作兜底。
     */
    private void sendWithPassiveFirst(String userId, String replyToMsgId, String text) {
        boolean useMarkdown = looksLikeMarkdown(text);
        if (useMarkdown) {
            sendPassive(userId, replyToMsgId, text, true);
        } else {
            sendPassive(userId, replyToMsgId, text, false);
        }
    }

    /** 粗略判断文本是否含 Markdown 语法（标题/加粗/列表/引用/代码/分隔线） */
    private boolean looksLikeMarkdown(String text) {
        if (text == null || text.isBlank()) return false;
        if (text.length() > 4000) return false; // 超长退化为文本，避免平台限制
        String[] lines = text.split("\\r?\\n");
        int md = 0;
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("#") || t.startsWith("-") || t.startsWith("*") || t.startsWith(">")) md++;
            else if (t.matches("^\\d+\\.\\s.*")) md++;
            else if (t.contains("**") || t.contains("`")) md++;
        }
        return md >= 2 || text.contains("> _调用工具：");
    }

    private void sendPassive(String userId, String replyToMsgId, String text, boolean markdown) {
        try {
            pruneEphemeralCaches(System.currentTimeMillis());
            ensureToken();
            String targetMsgId = replyToMsgId == null || replyToMsgId.isBlank() ? lastMsgIds.get(userId) : replyToMsgId;
            Long recvAt = replyToMsgId == null || replyToMsgId.isBlank()
                    ? lastRecvAt.get(userId) : receivedAtByMessage.get(replyCacheKey(userId, replyToMsgId));
            boolean withinWindow = targetMsgId != null
                    && recvAt != null
                    && (System.currentTimeMillis() - recvAt) < PASSIVE_WINDOW_MS;
            RestClient client = buildRestClient(apiBase, 15);
            ObjectNode body = objectMapper.createObjectNode();
            if (markdown) {
                ObjectNode md = body.putObject("markdown");
                md.put("content", text);
                body.put("msg_type", 2);
            } else {
                body.put("content", text);
                body.put("msg_type", 0);
            }
            if (withinWindow) {
                body.put("msg_id", targetMsgId);
                body.put("msg_seq", messageSequence.next());
            }
            client.post().uri(messagePath(userId), targetOpenid(userId))
                    .header("Authorization", "QQBot " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve().body(String.class);
            log.info("[qq] send{} -> {} ({} chars)", withinWindow ? "(passive)" : "(proactive)", userId,
                    text == null ? 0 : text.length());
        } catch (Exception e) {
            // 被动失败（msg_id 失效/限频等）→ 降级主动消息重试一次
            log.warn("[qq] passive send failed userId={}: {} → 降级主动消息", userId, e.getMessage());
            try {
                ensureToken();
                RestClient client = buildRestClient(apiBase, 15);
                ObjectNode body = objectMapper.createObjectNode();
                if (markdown) {
                    ObjectNode md = body.putObject("markdown");
                    md.put("content", text);
                    body.put("msg_type", 2);
                } else {
                    body.put("content", text);
                    body.put("msg_type", 0);
                }
                client.post().uri(messagePath(userId), targetOpenid(userId))
                        .header("Authorization", "QQBot " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body.toString())
                        .retrieve().body(String.class);
                log.info("[qq] send(proactive-retry) -> {} ({} chars)", userId, text == null ? 0 : text.length());
            } catch (Exception e2) {
                log.warn("[qq] proactive retry failed userId={}: {}", userId, e2.getMessage());
            }
        }
    }

    private String groupConversationId(String groupOpenid) {
        return GROUP_CONVERSATION_PREFIX + groupOpenid;
    }

    private boolean isGroupConversation(String conversationId) {
        return conversationId != null && conversationId.startsWith(GROUP_CONVERSATION_PREFIX);
    }

    private String targetOpenid(String conversationId) {
        return isGroupConversation(conversationId)
                ? conversationId.substring(GROUP_CONVERSATION_PREFIX.length())
                : conversationId;
    }

    private String messagePath(String conversationId) {
        return isGroupConversation(conversationId)
                ? "/v2/groups/{openid}/messages"
                : "/v2/users/{openid}/messages";
    }

    // ============ 流式回复（stream_messages，append 模式） ============

    /** 每用户的流式会话状态：streamMsgId / msgSeq / index / 是否已完成 */
    private static class StreamSessionState {
        volatile String streamMsgId;
        final int msgSeq;
        final long createdAtMillis = System.currentTimeMillis();
        volatile int index;
        volatile boolean hasPartialContent;
        volatile boolean done;
        volatile boolean failed;
        StreamSessionState(int msgSeq) { this.msgSeq = msgSeq; }
    }
    private final ConcurrentMap<String, StreamSessionState> streamStates = new ConcurrentHashMap<>();

    @Override
    public StreamReplySink createStreamSink(String userId, String msgId) {
        if (isGroupConversation(userId)) {
            return null;
        }
        pruneEphemeralCaches(System.currentTimeMillis());
        String replyToMsgId = msgId;
        Long recvAt = receivedAtByMessage.get(replyCacheKey(userId, msgId));
        boolean withinWindow = replyToMsgId != null
                && recvAt != null
                && (System.currentTimeMillis() - recvAt) < PASSIVE_WINDOW_MS;
        if (!withinWindow || replyToMsgId == null) {
            return null; // 被动窗口外无法流式，退化为普通回复
        }
        int msgSeq = messageSequence.next();
        StreamSessionState state = new StreamSessionState(msgSeq);
        streamStates.put(userId, state);
        String passiveMsgId = replyToMsgId;
        log.info("[qq] stream session start user={} msgSeq={}", userId, msgSeq);
        return new StreamReplySink() {
            @Override
            public void onPartial(String partial) {
                if (partial == null || partial.isBlank()) return;
                if (sendStreamFrame(userId, passiveMsgId, state, partial, 1)) { // GENERATING
                    state.hasPartialContent = true;
                }
            }

            @Override
            public void onDone(String fullText) {
                if (state.done) return;
                String finalContent = fullText == null ? "" : fullText;
                if (!finalContent.isBlank()) {
                    log.info("[qq] stream final generating frame user={} toolFooter={} len={}", userId,
                            finalContent.contains("调用工具："), finalContent.length());
                    sendStreamFrame(userId, passiveMsgId, state, finalContent, 1);
                }
                sendStreamFrame(userId, passiveMsgId, state, "", 10); // DONE
                state.done = true;
                streamStates.remove(userId);
                log.info("[qq] stream session done user={}", userId);
            }

            @Override
            public void onError(Throwable t) {
                state.failed = true;
                streamStates.remove(userId);
                log.warn("[qq] stream failed user={}: {}", userId, t.getMessage());
            }

            @Override
            public boolean isDone() {
                return state.done && !state.failed;
            }
        };
    }

    /** 发送一帧流式消息（append 模式：content_raw 为增量）；首帧成功后记录 stream_msg_id */
    private boolean sendStreamFrame(String userId, String msgId, StreamSessionState state, String contentRaw, int inputState) {
        try {
            ensureToken();
            RestClient client = buildRestClient(apiBase, 15);
            ObjectNode body = objectMapper.createObjectNode();
            body.put("input_mode", "append");
            body.put("input_state", inputState);
            body.put("content_type", "markdown");
            body.put("content_raw", contentRaw);
            body.put("msg_id", msgId);
            body.put("msg_seq", state.msgSeq);
            body.put("index", state.index++);
            if (state.streamMsgId != null) {
                body.put("stream_msg_id", state.streamMsgId);
            }
            String resp = client.post().uri("/v2/users/{openid}/stream_messages", userId)
                    .header("Authorization", "QQBot " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve().body(String.class);
            if (state.streamMsgId == null && resp != null) {
                JsonNode node = objectMapper.readTree(resp);
                String id = node.path("id").asText("");
                if (!id.isBlank()) state.streamMsgId = id;
            }
            log.info("[qq] stream frame user={} state={} idx={} len={}", userId, inputState, state.index - 1, contentRaw.length());
            return true;
        } catch (Exception e) {
            state.failed = true;
            log.warn("[qq] stream frame failed user={}: {}", userId, e.getMessage());
            return false;
        }
    }

    @Override
    public void shutdown() {
        running.set(false);
        stopHeartbeat();
        if (typingExecutor != null) { typingExecutor.shutdownNow(); typingExecutor = null; }
        typingTasks.clear();
        lastRecvAt.clear();
        lastMsgIds.clear();
        receivedAtByMessage.clear();
        receivedMessages.clear();
        streamStates.clear();
        WebSocket s = ws;
        if (s != null) {
            try { s.close(1000, "shutdown"); } catch (Exception ignored) { }
        }
    }
}
