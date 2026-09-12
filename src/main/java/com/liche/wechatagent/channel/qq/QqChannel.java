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
import com.liche.wechatagent.config.QqRuntimeProperties;
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
import org.springframework.web.client.RestClientResponseException;

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
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(name = "qq.enabled", havingValue = "true")
public class QqChannel implements WeChatChannel {

    private static final Logger log = LoggerFactory.getLogger(QqChannel.class);
    private static final String TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";
    private static final String API_BASE = "https://api.sgroup.qq.com";
    private static final String SANDBOX_API_BASE = "https://sandbox.api.sgroup.qq.com";
    private static final String GROUP_CONVERSATION_PREFIX = "qq-group:";
    private static final int INTENT_FULL = (1 << 0) | (1 << 1) | (1 << 30) | (1 << 12) | (1 << 25) | (1 << 26);
    private final String appId;
    private final String clientSecret;
    private final String apiBase;
    private final boolean groupEnabled;
    private final long apiConnectTimeoutSeconds;
    private final long apiReadTimeoutSeconds;
    private final long websocketConnectTimeoutSeconds;
    private final long passiveWindowMillis;
    private final long typingKeepaliveMillis;
    private final long ephemeralCacheTtlMillis;
    private final long streamStateTtlMillis;
    private final int maxConversationCacheEntries;
    private final int maxMessageCacheEntries;
    private final long reconnectDelayMillis;
    private final long[] reconnectBackoffMillis;
    private final int tokenDefaultExpireSeconds;
    private final long tokenRefreshLeadMillis;
    private final int defaultHeartbeatMillis;
    private final int inputNotifySeconds;
    private final int markdownMaxChars;
    private final String userAgent;
    /** 入站按用户限流（固定窗口）。见 {@link QqInboundRateLimiter}。 */
    private final QqInboundRateLimiter inboundRateLimiter;
    /** 主动消息日额度账本；仅测试用的构造器会传 null。 */
    private final QqProactiveQuota proactiveQuota;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentOrchestrator orchestrator;
    private final AtomicBoolean running = new AtomicBoolean(true);
    /** Ensures only one gateway connection loop can be active at a time. */
    private final AtomicBoolean connectLoopActive = new AtomicBoolean(false);
    /** Coalesces simultaneous disconnect callbacks into one delayed reconnect. */
    private final AtomicBoolean reconnectPending = new AtomicBoolean(false);
    private final QqMessageSequence messageSequence = new QqMessageSequence();

    private volatile String accessToken;
    private volatile long tokenExpireAtMs;
    private volatile String selfOpenid; // 机器人自己的 openid（从 READY 记录，用于过滤 bot 消息回显）
    private volatile String sessionId;   // 网关会话 id（断线后 RESUME 恢复用）
    private volatile int lastSeq = 0;    // 最后收到的消息序列号（RESUME 用）
    private volatile WebSocket ws;
    private final AtomicBoolean commandPanelConfigured = new AtomicBoolean(false);
    private final AtomicLong textSendSuccessCount = new AtomicLong();
    private final AtomicLong textSendFailureCount = new AtomicLong();
    private final AtomicLong mediaSendSuccessCount = new AtomicLong();
    private final AtomicLong mediaSendFailureCount = new AtomicLong();
    private final AtomicLong textSendDurationMillis = new AtomicLong();
    private final AtomicLong mediaSendDurationMillis = new AtomicLong();
    private final AtomicLong apiErrorCount = new AtomicLong();
    private final AtomicLong reconnectSuccessCount = new AtomicLong();
    private final AtomicLong reconnectFailureCount = new AtomicLong();
    private final AtomicLong tokenRefreshFailureCount = new AtomicLong();
    private final AtomicLong quoteLookupSuccessCount = new AtomicLong();
    private final AtomicLong quoteLookupFailureCount = new AtomicLong();
    private final AtomicLong chunkUploadFailureCount = new AtomicLong();
    private final AtomicLong heartbeatFailureCount = new AtomicLong();
    private final AtomicLong gatewayFrameParseFailureCount = new AtomicLong();
    private final AtomicLong gatewayIdentifyFailureCount = new AtomicLong();
    private final AtomicLong gatewayResumeFailureCount = new AtomicLong();
    /** 入站被限流丢弃的消息数（含那一条礼貌提示） */
    private final AtomicLong inboundRateLimitedCount = new AtomicLong();
    /** 发送结果未知（超时/连接中断）而按"已发出"处理的次数 */
    private final AtomicLong sendResultUnknownCount = new AtomicLong();
    private final ConcurrentMap<String, AtomicLong> apiErrorsByStatus = new ConcurrentHashMap<>();
    private volatile long connectedAtMillis;
    private volatile long lastGatewayEventAtMillis;
    /** 网关在 HELLO 里给的心跳间隔，用来判断"连接还在但已经不回话了" */
    private volatile long heartbeatIntervalMillis;
    private volatile String lastApiError;
    @Value("${qq.command-panel-enabled:true}")
    private boolean commandPanelEnabled;
    @Value("${media.storage.max-file-bytes:20971520}")
    private long maxOutboundMediaBytes;
    @Value("${qq.max-message-chars:4000}")
    private int maxMessageChars;
    private final QqWebSocketClient gatewayClient;
    private final QqChunkedMediaUploader chunkedMediaUploader;
    private volatile ScheduledExecutorService heartbeatExecutor;
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile ScheduledExecutorService typingExecutor;
    /** userId -> 最近收到消息的时间戳（用于判断被动窗口） */
    private final ConcurrentMap<String, Long> lastRecvAt = new ConcurrentHashMap<>();
    /** userId -> 最近消息 id（被动回复用） */
    private final ConcurrentMap<String, String> lastMsgIds = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> lastBotMsgIds = new ConcurrentHashMap<>();
    /** conversationId + msgId -> 接收时间，用于指定消息的被动回复窗口判断。 */
    private final ConcurrentMap<String, Long> receivedAtByMessage = new ConcurrentHashMap<>();
    /** userId -> 输入状态续期任务 */
    private final ConcurrentMap<String, ScheduledFuture<?>> typingTasks = new ConcurrentHashMap<>();
    /** Only short-lived, per-user copies of received messages, used when QQ quote lookup is temporarily unavailable. */
    private final ConcurrentMap<String, ReceivedQuote> receivedMessages = new ConcurrentHashMap<>();
    /** User-scoped continuation pages for long replies; never shared between conversations. */
    private final ConcurrentMap<String, PendingPages> pendingPages = new ConcurrentHashMap<>();
    private record PendingPages(List<String> pages, long expiresAt) { }

    private record ReceivedQuote(QqQuoteMessage message, long expiresAt) {
    }

    @Autowired
    public QqChannel(@Value("${qq.app-id}") String appId,
                     @Value("${qq.client-secret}") String clientSecret,
                     @Value("${qq.sandbox:false}") boolean sandbox,
                     @Value("${qq.group-enabled:false}") boolean groupEnabled,
                     @Value("${qq.api-connect-timeout-seconds:5}") long apiConnectTimeoutSeconds,
                     @Value("${qq.api-read-timeout-seconds:15}") long apiReadTimeoutSeconds,
                     @Value("${qq.websocket-connect-timeout-seconds:10}") long websocketConnectTimeoutSeconds,
                     @Value("${qq.passive-window-ms:3600000}") long passiveWindowMillis,
                     @Value("${qq.typing-keepalive-ms:50000}") long typingKeepaliveMillis,
                     @Value("${qq.ephemeral-cache-ttl-ms:1800000}") long ephemeralCacheTtlMillis,
                     @Value("${qq.stream-state-ttl-ms:600000}") long streamStateTtlMillis,
                     @Value("${qq.max-conversation-cache-entries:2048}") int maxConversationCacheEntries,
                     @Value("${qq.max-message-cache-entries:4096}") int maxMessageCacheEntries,
                     @Lazy AgentOrchestrator orchestrator,
                     QqRuntimeProperties runtimeProperties,
                     QqProactiveQuota proactiveQuota) {
        this(appId, clientSecret, sandbox, groupEnabled, apiConnectTimeoutSeconds, apiReadTimeoutSeconds,
                websocketConnectTimeoutSeconds, passiveWindowMillis, typingKeepaliveMillis,
                ephemeralCacheTtlMillis, streamStateTtlMillis, maxConversationCacheEntries,
                maxMessageCacheEntries, orchestrator, runtimeProperties, proactiveQuota, true);
    }

    QqChannel(String appId, String clientSecret, boolean sandbox, AgentOrchestrator orchestrator) {
        this(appId, clientSecret, sandbox, false,
                5, 15, 10, 60 * 60 * 1000L, 50_000L, 30 * 60 * 1000L,
                10 * 60 * 1000L, 2_048, 4_096, orchestrator, new QqRuntimeProperties(), null, true);
    }

    private QqChannel(String appId, String clientSecret, boolean sandbox, boolean groupEnabled,
                      long apiConnectTimeoutSeconds, long apiReadTimeoutSeconds,
                      long websocketConnectTimeoutSeconds, long passiveWindowMillis,
                      long typingKeepaliveMillis, long ephemeralCacheTtlMillis,
                      long streamStateTtlMillis, int maxConversationCacheEntries,
                      int maxMessageCacheEntries, AgentOrchestrator orchestrator,
                      QqRuntimeProperties runtimeProperties, QqProactiveQuota proactiveQuota,
                      boolean ignored) {
        this.appId = appId;
        this.clientSecret = clientSecret;
        this.apiBase = sandbox ? SANDBOX_API_BASE : API_BASE;
        this.groupEnabled = groupEnabled;
        this.apiConnectTimeoutSeconds = bounded(apiConnectTimeoutSeconds, 1, 120,
                5);
        this.apiReadTimeoutSeconds = bounded(apiReadTimeoutSeconds, 1, 600, 15);
        this.websocketConnectTimeoutSeconds = bounded(websocketConnectTimeoutSeconds, 1, 120,
                10);
        this.passiveWindowMillis = bounded(passiveWindowMillis, 1_000, 3_600_000, 60 * 60 * 1000L);
        this.typingKeepaliveMillis = bounded(typingKeepaliveMillis, 1_000, 60_000, 50_000L);
        this.ephemeralCacheTtlMillis = bounded(ephemeralCacheTtlMillis, 1_000, 86_400_000,
                30 * 60 * 1000L);
        this.streamStateTtlMillis = bounded(streamStateTtlMillis, 1_000, 86_400_000,
                10 * 60 * 1000L);
        this.maxConversationCacheEntries = bounded(maxConversationCacheEntries, 1, 100_000,
                2_048);
        this.maxMessageCacheEntries = bounded(maxMessageCacheEntries, 1, 200_000,
                4_096);
        QqRuntimeProperties policies = runtimeProperties == null ? new QqRuntimeProperties() : runtimeProperties;
        this.reconnectDelayMillis = bounded(policies.getReconnectDelayMs(), 250, 300_000,
                QqRuntimeProperties.DEFAULT_RECONNECT_DELAY_MS);
        this.reconnectBackoffMillis = parseBackoff(policies.getReconnectBackoffMs());
        this.tokenDefaultExpireSeconds = bounded(policies.getTokenDefaultExpireSeconds(), 60, 86_400,
                QqRuntimeProperties.DEFAULT_TOKEN_EXPIRE_SECONDS);
        this.tokenRefreshLeadMillis = bounded(policies.getTokenRefreshLeadMs(), 0, 3_600_000,
                QqRuntimeProperties.DEFAULT_TOKEN_REFRESH_LEAD_MS);
        this.defaultHeartbeatMillis = bounded(policies.getDefaultHeartbeatMs(), 1_000, 600_000,
                QqRuntimeProperties.DEFAULT_HEARTBEAT_MS);
        this.heartbeatIntervalMillis = this.defaultHeartbeatMillis;
        this.inputNotifySeconds = bounded(policies.getInputNotifySeconds(), 1, 60,
                QqRuntimeProperties.DEFAULT_INPUT_NOTIFY_SECONDS);
        this.markdownMaxChars = bounded(policies.getMarkdownMaxChars(), 256, 100_000,
                QqRuntimeProperties.DEFAULT_MARKDOWN_MAX_CHARS);
        this.userAgent = policies.getUserAgent() == null || policies.getUserAgent().isBlank()
                ? QqRuntimeProperties.DEFAULT_USER_AGENT : policies.getUserAgent().trim();
        this.inboundRateLimiter = new QqInboundRateLimiter(
                bounded(policies.getInboundRateLimitPerMinute(), 0, 100_000,
                        QqRuntimeProperties.DEFAULT_INBOUND_RATE_LIMIT_PER_MINUTE),
                INBOUND_RATE_WINDOW_MILLIS, this.maxConversationCacheEntries);
        this.proactiveQuota = proactiveQuota;
        this.gatewayClient = new QqWebSocketClient(this.websocketConnectTimeoutSeconds, this.userAgent);
        this.chunkedMediaUploader = new QqChunkedMediaUploader(objectMapper, this.apiConnectTimeoutSeconds, error -> {
            chunkUploadFailureCount.incrementAndGet();
            apiErrorCount.incrementAndGet();
            recordApiError(error);
        });
        this.orchestrator = orchestrator;
    }

    @Override
    public String channel() { return "qq"; }

    @EventListener(ApplicationReadyEvent.class)
    public void start() { startConnectLoop("qq-connect"); }

    private void startConnectLoop(String threadName) {
        if (!running.get() || !connectLoopActive.compareAndSet(false, true)) {
            return;
        }
        new Thread(() -> {
            try {
                connectLoop();
            } finally {
                connectLoopActive.set(false);
            }
        }, threadName).start();
    }

    private void connectLoop() {
        int attempt = 0;
        while (running.get()) {
            try {
                ensureToken();
                String wsUrl = fetchGatewayUrl();
                log.info("QQ bot connecting: {}", wsUrl);
                connectWebSocket(wsUrl);
                reconnectSuccessCount.incrementAndGet();
                attempt = 0;
                return;
            } catch (Exception e) {
                reconnectFailureCount.incrementAndGet();
                log.warn("QQ bot connect failed: {}", e.getMessage());
                long delay = reconnectBackoffMillis[Math.min(attempt, reconnectBackoffMillis.length - 1)];
                attempt++;
                try { Thread.sleep(delay); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private void connectWebSocket(String wsUrl) throws Exception {
        this.ws = gatewayClient.connect(wsUrl, accessToken, new QqWebSocketListener());
        log.info("QQ bot WebSocket connected");
    }

    /** 连续多少个心跳周期收不到**任何**帧（含心跳 ACK）就判定为半开连接 */
    private static final long SILENT_INTERVALS = 3;

    /** 入站限流的窗口长度（固定窗口） */
    private static final long INBOUND_RATE_WINDOW_MILLIS = 60_000L;

    /** 入站被限流时的礼貌提示；同一个限流窗口内只发这一条 */
    private static final String INBOUND_RATE_LIMIT_NOTICE =
            "你发得有点快，我先一条条处理，过一会儿再发给我吧。";

    /**
     * 连接引用还在、心跳也发得出去，但已经很久没收到任何帧 —— 半开连接 / NAT 静默丢包的判据。
     *
     * <p>只看 {@code ws != null} 会"假在线"：socket 没关、写心跳也不报错，可对端早就不回话了
     * （心跳 ACK 也算帧，会刷新时间戳）。这种情况 OkHttp 的 {@code onFailure}/{@code onClosed}
     * **都不会回调**，所以既没人重连、也没人告警。
     */
    private boolean gatewayWentSilent() {
        if (ws == null || !running.get()) {
            return false;   // 压根没连接：交给 connectLoop 的退避重试
        }
        long last = lastGatewayEventAtMillis;
        if (last == 0) {
            return false;   // 刚连上、还没收到第一帧
        }
        return System.currentTimeMillis() - last >= Math.max(1, heartbeatIntervalMillis) * SILENT_INTERVALS;
    }

    public boolean isGatewayConnected() {
        if (ws == null || !running.get()) {
            return false;
        }
        return !gatewayWentSilent();
    }

    public Map<String, Object> healthSnapshot() {
        Map<String, Object> metrics = new java.util.LinkedHashMap<>();
        metrics.put("connected", isGatewayConnected());
        metrics.put("textSendSuccess", textSendSuccessCount.get());
        metrics.put("textSendFailure", textSendFailureCount.get());
        metrics.put("mediaSendSuccess", mediaSendSuccessCount.get());
        metrics.put("mediaSendFailure", mediaSendFailureCount.get());
        metrics.put("apiErrors", apiErrorCount.get());
        metrics.put("reconnectSuccess", reconnectSuccessCount.get());
        metrics.put("reconnectFailure", reconnectFailureCount.get());
        metrics.put("tokenRefreshFailure", tokenRefreshFailureCount.get());
        metrics.put("quoteLookupSuccess", quoteLookupSuccessCount.get());
        metrics.put("quoteLookupFailure", quoteLookupFailureCount.get());
        metrics.put("chunkUploadFailure", chunkUploadFailureCount.get());
        metrics.put("heartbeatFailure", heartbeatFailureCount.get());
        metrics.put("gatewayFrameParseFailure", gatewayFrameParseFailureCount.get());
        metrics.put("gatewayIdentifyFailure", gatewayIdentifyFailureCount.get());
        metrics.put("gatewayResumeFailure", gatewayResumeFailureCount.get());
        metrics.put("inboundRateLimited", inboundRateLimitedCount.get());
        metrics.put("inboundRateLimitPerMinute", inboundRateLimiter.limitPerWindow());
        metrics.put("sendResultUnknown", sendResultUnknownCount.get());
        metrics.put("proactiveToday", proactiveQuota == null ? -1L : proactiveQuota.used());
        metrics.put("proactiveDailyLimit", proactiveQuota == null ? 0 : proactiveQuota.limit());
        metrics.put("apiErrorsByStatus", apiErrorsByStatus.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> e.getValue().get())));
        metrics.put("connectedAt", connectedAtMillis == 0 ? "" : java.time.Instant.ofEpochMilli(connectedAtMillis).toString());
        metrics.put("lastGatewayEventAt", lastGatewayEventAtMillis == 0 ? "" : java.time.Instant.ofEpochMilli(lastGatewayEventAtMillis).toString());
        metrics.put("lastApiError", lastApiError == null ? "" : lastApiError);
        metrics.put("textSendAverageMs", averageMillis(textSendDurationMillis, textSendSuccessCount.get() + textSendFailureCount.get()));
        metrics.put("mediaSendAverageMs", averageMillis(mediaSendDurationMillis, mediaSendSuccessCount.get() + mediaSendFailureCount.get()));
        return Map.copyOf(metrics);
    }

    private long averageMillis(AtomicLong total, long count) {
        return count == 0 ? 0 : total.get() / count;
    }

    private void recordApiError(Throwable error) {
        String status = "exception";
        if (error instanceof RestClientResponseException response) {
            status = String.valueOf(response.getStatusCode().value());
            // 401/403 说明本地这个 token 已经不认了（典型场景：本机 JAR 与容器双开抢网关，
            // 另一个实例拿到新 token 把旧的顶掉）。原来的 ensureToken 只看时间有没有到期，
            // 于是两次尝试都用同一个坏 token，一路失败到两小时后自然过期为止。
            int code = response.getStatusCode().value();
            if (code == 401 || code == 403) {
                invalidateToken("服务端拒绝（" + code + "）");
            }
        }
        apiErrorsByStatus.computeIfAbsent(status, ignored -> new AtomicLong()).incrementAndGet();
        String message = error == null ? "unknown" : error.getClass().getSimpleName();
        lastApiError = message.length() > 120 ? message.substring(0, 120) : message;
    }

    /** 作废本地缓存的 access_token，让下一次 ensureToken() 重新去换一个 */
    private void invalidateToken(String reason) {
        if (accessToken != null) {
            log.warn("QQ access_token 已作废（{}），下次请求会重新获取", reason);
        }
        accessToken = null;
        tokenExpireAtMs = 0;
    }

    private void reconnect() {
        if (!running.get() || !reconnectPending.compareAndSet(false, true)) return;
        stopHeartbeat();
        new Thread(() -> {
            try {
                Thread.sleep(reconnectDelayMillis);
                reconnectPending.set(false);
                startConnectLoop("qq-reconnect");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                reconnectPending.set(false);
            }
        }, "qq-reconnect-delay").start();
    }

    public void requestReconnect() { reconnect(); }

    public void cleanupCaches() { pruneEphemeralCaches(System.currentTimeMillis()); }

    private synchronized void ensureToken() {
        if (accessToken != null && System.currentTimeMillis() < tokenExpireAtMs - tokenRefreshLeadMillis) return;
        try {
            RestClient client = buildRestClient(TOKEN_URL);
            ObjectNode body = objectMapper.createObjectNode();
            body.put("appId", appId);
            body.put("clientSecret", clientSecret);
            String resp = client.post().uri("").contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString()).retrieve().body(String.class);
            JsonNode node = objectMapper.readTree(resp);
            accessToken = node.path("access_token").asText("");
            int expiresIn = node.path("expires_in").asInt(tokenDefaultExpireSeconds);
            tokenExpireAtMs = System.currentTimeMillis() + expiresIn * 1000L;
            log.info("QQ access_token ok, expires {}s", expiresIn);
        } catch (Exception e) {
            tokenRefreshFailureCount.incrementAndGet();
            apiErrorCount.incrementAndGet();
            recordApiError(e);
            throw new RuntimeException("QQ access_token failed: " + e.getMessage(), e);
        }
    }

    private String fetchGatewayUrl() {
        try {
            RestClient client = buildRestClient(apiBase);
            String resp = client.get().uri("/gateway")
                    .header("Authorization", "QQBot " + accessToken)
                    .retrieve().body(String.class);
            JsonNode node = objectMapper.readTree(resp);
            return node.path("url").asText("");
        } catch (Exception e) {
            apiErrorCount.incrementAndGet();
            recordApiError(e);
            throw new RuntimeException("QQ gateway 解析失败", e);
        }
    }

    private RestClient buildRestClient(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(apiConnectTimeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(apiReadTimeoutSeconds));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    private class QqWebSocketListener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            closeResponse(response);
            connectedAtMillis = System.currentTimeMillis();
            lastGatewayEventAtMillis = connectedAtMillis;
            log.info("QQ WebSocket onOpen");
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            lastGatewayEventAtMillis = System.currentTimeMillis();
            handleFrame(text, webSocket);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            lastGatewayEventAtMillis = System.currentTimeMillis();
            if (QqChannel.this.ws == webSocket) QqChannel.this.ws = null;
            log.warn("QQ WebSocket closed: code={} reason={}", code, reason);
            if (code == 1000 && ("client shutdown".equalsIgnoreCase(reason)
                    || "shutdown".equalsIgnoreCase(reason))) {
                return;
            }
            if (code == 4004) {
                log.error("QQ 鉴权失败：请检查 QQ 开放平台 IP 白名单（当前出口 IP）与 AppID/AppSecret");
                running.set(false);
                return;
            }
            reconnect();
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            lastGatewayEventAtMillis = System.currentTimeMillis();
            apiErrorCount.incrementAndGet();
            recordApiError(t);
            if (QqChannel.this.ws == webSocket) QqChannel.this.ws = null;
            closeResponse(response);
            log.warn("QQ WebSocket failure: {}", t.getMessage());
            reconnect();
        }
    }

    private void closeResponse(Response response) {
        if (response != null) {
            try {
                response.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void closeWebSocketClient() {
        gatewayClient.close();
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
            gatewayIdentifyFailureCount.incrementAndGet();
            recordApiError(e);
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
            gatewayResumeFailureCount.incrementAndGet();
            recordApiError(e);
            log.warn("QQ RESUME failed: {}", e.getMessage());
        }
    }

    private void handleFrame(String text, WebSocket webSocket) {
        try {
            JsonNode node = objectMapper.readTree(text);
            int op = node.path("op").asInt(-1);
            switch (op) {
                case 10 -> handleHello(node, webSocket);
                case 7, 9 -> handleReconnectOperation(op, node);
                case 0 -> handleDispatch(node);
                default -> { }
            }
        } catch (Exception e) {
            gatewayFrameParseFailureCount.incrementAndGet();
            log.warn("QQ frame parse failed", e);
        }
    }

    // Handles the gateway HELLO frame and starts the heartbeat.
    private void handleHello(JsonNode node, WebSocket webSocket) {
        if (sessionId != null && lastSeq > 0) sendResume(webSocket); else sendIdentify(webSocket);
        int interval = node.path("d").path("heartbeat_interval").asInt(defaultHeartbeatMillis);
        this.heartbeatIntervalMillis = interval;
        startHeartbeat(interval);
    }

    // Handles gateway reconnect and invalid-session operations.
    private void handleReconnectOperation(int op, JsonNode node) {
        if (op == 9 && !node.path("d").asBoolean(false)) { sessionId = null; lastSeq = 0; }
        log.info("QQ gateway {} , reconnecting", op == 7 ? "RECONNECT requested" : "INVALID_SESSION");
        reconnect();
    }

    // Routes dispatch events to channel-specific inbound message handlers.
    private void handleDispatch(JsonNode node) {
        int sequence = node.path("s").asInt(0);
        if (sequence > 0) lastSeq = sequence;
        String event = node.path("t").asText("");
        if ("READY".equals(event)) {
            selfOpenid = node.path("d").path("user").path("id").asText("");
            sessionId = node.path("d").path("session_id").asText("");
            log.info("QQ gateway READY: session established, botId={}", selfOpenid);
            configureC2cCommandPanel();
            configureC2cCustomMenu();
        } else if ("C2C_MESSAGE_CREATE".equals(event)) {
            handleC2cMessage(node.path("d"));
        } else if ("GROUP_AT_MESSAGE_CREATE".equals(event) && groupEnabled) {
            handleGroupMessage(node.path("d"));
        } else if (!event.isBlank()) {
            log.info("QQ event: t={}", event);
        }
    }

    private void configureC2cCommandPanel() {
        if (!commandPanelEnabled) return;
        if (!commandPanelConfigured.compareAndSet(false, true)) return;
        try {
            ensureToken();
            String existing = buildRestClient(apiBase).get().uri(uriBuilder -> uriBuilder
                            .path("/v2/panels")
                            .queryParam("scope", "c2c")
                            .queryParam("limit", "50")
                            .build())
                    .header("Authorization", "QQBot " + accessToken).retrieve().body(String.class);
            if (existing != null && existing.contains("wechat-agent-c2c")) {
                log.info("QQ C2C command panel already exists");
                return;
            }
            List<Map<String, Object>> items = List.of(
                    Map.of("type", "command", "name", "帮助", "desc", "查看使用说明"),
                    Map.of("type", "command", "name", "查看记忆", "desc", "查看自动记忆"),
                    Map.of("type", "command", "name", "查看提醒", "desc", "查看待执行提醒"),
                    Map.of("type", "command", "name", "开启自动记忆", "desc", "开启长期记忆"),
                    Map.of("type", "command", "name", "关闭自动记忆", "desc", "关闭长期记忆"),
                    Map.of("type", "command", "name", "删除记忆", "desc", "按关键词删除记忆"),
                    Map.of("type", "command", "name", "设置助手人设", "desc", "调整说话方式"),
                    Map.of("type", "command", "name", "开启每日复盘", "desc", "开启每日主动关怀"),
                    Map.of("type", "command", "name", "开启每周复盘", "desc", "开启每周主动关怀"),
                    Map.of("type", "command", "name", "关闭主动关怀", "desc", "关闭主动关怀"));
            Map<String, Object> panel = Map.of("items", items, "remark", "wechat-agent-c2c");
            Map<String, Object> payload = Map.of("scope", "c2c", "target_type", "all", "panel", panel);
            String payloadJson = objectMapper.writeValueAsString(payload);
            buildRestClient(apiBase).post().uri("/v2/panels")
                    .header("Authorization", "QQBot " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payloadJson).retrieve().body(String.class);
            log.info("QQ C2C command panel configured");
        } catch (RestClientResponseException exception) {
            apiErrorCount.incrementAndGet();
            recordApiError(exception);
            logPanelApiFailure(exception);
        } catch (Exception exception) {
            apiErrorCount.incrementAndGet();
            recordApiError(exception);
            commandPanelConfigured.set(false);
            log.warn("QQ C2C command panel configuration failed: {}", exception.getMessage());
        }
    }

    private void logPanelApiFailure(RestClientResponseException exception) {
        commandPanelConfigured.set(false);
        String response = exception.getResponseBodyAsString();
        try {
            JsonNode error = objectMapper.readTree(response);
            log.warn("QQ C2C command panel configuration failed: httpStatus={} qqCode={} errCode={} traceId={} message={}",
                    exception.getStatusCode().value(), error.path("code").asInt(0),
                    error.path("err_code").asLong(0), error.path("trace_id").asText(""),
                    error.path("message").asText(""));
        } catch (Exception ignored) {
            log.warn("QQ C2C command panel configuration failed: httpStatus={} response={}",
                    exception.getStatusCode().value(), response);
        }
    }

    private void configureC2cCustomMenu() {
        if (!commandPanelEnabled) return;
        try {
            ensureToken();
            List<Map<String, Object>> items = List.of(
                    Map.of("type", "send_message", "name", "帮助", "send_message", "帮助"),
                    Map.of("type", "send_message", "name", "记忆", "send_message", "查看记忆"),
                    Map.of("type", "send_message", "name", "提醒", "send_message", "查看提醒"),
                    Map.of("type", "send_message", "name", "记忆开", "send_message", "开启自动记忆"),
                    Map.of("type", "send_message", "name", "记忆关", "send_message", "关闭自动记忆"),
                    Map.of("type", "send_message", "name", "删记忆", "send_message", "删除记忆"),
                    Map.of("type", "send_message", "name", "设人设", "send_message", "设置助手人设"),
                    Map.of("type", "send_message", "name", "日复盘", "send_message", "开启每日复盘"),
                    Map.of("type", "send_message", "name", "周复盘", "send_message", "开启每周复盘"),
                    Map.of("type", "send_message", "name", "关怀关", "send_message", "关闭主动关怀"));
            Map<String, Object> payload = Map.of("menu", Map.of("items", items));
            buildRestClient(apiBase).put().uri("/v2/menu")
                    .header("Authorization", "QQBot " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(payload))
                    .retrieve().body(String.class);
            log.info("QQ C2C custom menu configured");
        } catch (RestClientResponseException exception) {
            apiErrorCount.incrementAndGet();
            recordApiError(exception);
            logPanelApiFailure(exception);
        } catch (Exception exception) {
            apiErrorCount.incrementAndGet();
            recordApiError(exception);
            log.warn("QQ C2C custom menu configuration failed: {}", exception.getMessage());
        }
    }

    // Converts a direct-message gateway event into an isolated inbound message.
    private void handleC2cMessage(JsonNode data) {
        QqMessageMapper.DirectMessage message = QqMessageMapper.direct(data, selfOpenid);
        String openid = message.openid();
        String msgId = message.messageId();
        String content = message.content();
        if (message.bot()) { log.info("[qq] ignore bot-self msg -> {}", openid); return; }
        if (openid.isBlank()) return;
        QqAttachmentParser.Payload payload = message.attachments();
        if (payload.empty() && content.isBlank()) { log.info("[qq] recv (empty, no attachments) -> {}", openid); return; }
        if (!allowInbound(openid, msgId)) return;
        // 先记住被动窗口再处理「继续」：分页回复同样是对这条 msg_id 的被动回复，
        // 原来放在后面会让每一页都走主动消息（白耗主动配额，配额用光后分页直接发不出去）
        rememberReplyWindow(openid, msgId);
        if (handleContinuationRequest(openid, content, data.path("id").asText(""))) return;
        pendingPages.remove(openid);
        startTyping(openid);
        rememberReceivedMessage(openid, msgId, content, payload.images(), payload.attachments());
        log.info("[qq] inbound metadata user={} msg={} fields={}", openid, msgId, fieldNames(data));
        log.info("[qq] inbound element schema user={} msg={} schema={}", openid, msgId,
                describeJsonShape(data.path("msg_elements"), 0));
        QqQuoteMessage quote = resolveQuote(openid, data);
        InboundMessage inbound = payload.empty() && quoteEmpty(quote)
                ? InboundMessage.text(msgId, openid, content, "qq", "qq")
                : InboundMessage.textWithQuote(msgId, openid, content, "qq", "qq", payload.images(), payload.attachments(),
                quote.content(), quote.imageUrls(), quote.attachments());
        log.info("[qq] recv(image x{}, file x{}, quote={} chars/{} images) -> {}", payload.images().size(),
                payload.attachments().size(), quote.content().length(), quote.imageUrls().size(), openid);
        orchestrator.onInbound(inbound);
    }

    /**
     * 入站按用户限流：一个限流窗口内超过 {@code qq.inbound-rate-limit-per-minute} 条就
     * **先礼貌回一条、再静默丢弃**其余（提示只发一次，否则提示本身成了刷屏）。
     *
     * <p>限流提示走 {@link #sendPassive}，算被动回复、不占主动配额。
     * 返回 false 表示这条消息不进入 Agent。
     */
    private boolean allowInbound(String userId, String msgId) {
        if (!inboundRateLimiter.enabled()) {
            return true;
        }
        QqInboundRateLimiter.Decision decision = inboundRateLimiter.tryAcquire(userId, System.currentTimeMillis());
        if (decision == QqInboundRateLimiter.Decision.ALLOW) {
            return true;
        }
        inboundRateLimitedCount.incrementAndGet();
        if (decision == QqInboundRateLimiter.Decision.NOTIFY) {
            log.warn("[qq] 入站限流：user={} 一分钟内超过 {} 条，只回一条提示并忽略其余消息",
                    userId, inboundRateLimiter.limitPerWindow());
            rememberReplyWindow(userId, msgId);
            sendPassive(userId, msgId, INBOUND_RATE_LIMIT_NOTICE, false);
        } else {
            log.info("[qq] 入站限流：忽略消息 user={} msg={}", userId, msgId);
        }
        return false;
    }

    private boolean handleContinuationRequest(String userId, String content, String replyToMsgId) {
        if (!isContinuationCommand(content)) return false;
        PendingPages pending = pendingPages.get(userId);
        if (pending == null || pending.expiresAt() <= System.currentTimeMillis() || pending.pages().isEmpty()) {
            if (pending != null) pendingPages.remove(userId, pending);
            return false;
        }
        String page = pending.pages().getFirst();
        List<String> remaining = pending.pages().subList(1, pending.pages().size());
        boolean sent = sendPassive(userId, replyToMsgId, page, looksLikeMarkdown(page));
        if (sent) {
            if (remaining.isEmpty()) pendingPages.remove(userId, pending);
            else pendingPages.put(userId, new PendingPages(List.copyOf(remaining), pending.expiresAt()));
        }
        return sent;
    }

    private boolean isContinuationCommand(String content) {
        if (content == null) return false;
        String value = content.trim().toLowerCase(java.util.Locale.ROOT);
        return value.equals("继续") || value.equals("下一页") || value.equals("继续查看")
                || value.equals("/next") || value.equals("/more");
    }

    // Converts a group mention event while keeping the group conversation scope.
    private void handleGroupMessage(JsonNode data) {
        QqMessageMapper.GroupMessage message = QqMessageMapper.group(data, selfOpenid);
        String groupOpenid = message.groupOpenid();
        String memberOpenid = message.memberOpenid();
        String msgId = message.messageId();
        String content = message.content();
        if (message.bot()) { log.info("[qq] ignore bot-self group msg -> group={}", groupOpenid); return; }
        if (groupOpenid.isBlank() || memberOpenid.isBlank()) return;
        QqAttachmentParser.Payload payload = message.attachments();
        if (payload.empty() && content.isBlank()) { log.info("[qq] recv group (empty, no attachments) -> {}", groupOpenid); return; }
        String conversationId = groupConversationId(groupOpenid);
        String memberContent = "【群成员 " + memberOpenid + "】" + (content.isBlank() ? "[图片]" : content);
        rememberReplyWindow(conversationId, msgId);
        InboundMessage inbound = payload.empty()
                ? InboundMessage.text(msgId, conversationId, memberContent, "qq", "qq")
                : InboundMessage.textWithAttachments(msgId, conversationId, memberContent, "qq", "qq",
                payload.images(), payload.attachments());
        log.info("[qq] recv group={} member={} ({} chars, image x{}, file x{})", groupOpenid, memberOpenid,
                content.length(), payload.images().size(), payload.attachments().size());
        orchestrator.onInbound(inbound);
    }

    private boolean quoteEmpty(QqQuoteMessage quote) {
        return quote == null || (quote.content().isBlank() && quote.imageUrls().isEmpty() && quote.attachments().isEmpty());
    }

    private void rememberReceivedMessage(String userId, String messageId, String content, List<String> images,
                                         List<InboundAttachment> attachments) {
        if (messageId == null || messageId.isBlank()) return;
        long now = System.currentTimeMillis();
        receivedMessages.put(quoteCacheKey(userId, messageId), new ReceivedQuote(new QqQuoteMessage(messageId,
                content == null ? "" : content, images == null ? List.of() : List.copyOf(images),
                attachments == null ? List.of() : List.copyOf(attachments)), now + ephemeralCacheTtlMillis));
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
            RestClient client = buildRestClient(apiBase);
            String response = client.get().uri("/v2/users/{openid}/messages/{messageId}", userId, embedded.messageId())
                    .header("Authorization", "QQBot " + accessToken).retrieve().body(String.class);
            QqQuoteMessage fetched = QqQuoteMessage.fromLookup(objectMapper.readTree(response), embedded.messageId());
            if (!fetched.content().isBlank() || !fetched.imageUrls().isEmpty() || !fetched.attachments().isEmpty()) {
                quoteLookupSuccessCount.incrementAndGet();
                rememberReceivedMessage(userId, fetched.messageId(), fetched.content(), fetched.imageUrls(), fetched.attachments());
                return fetched;
            }
            quoteLookupFailureCount.incrementAndGet();
        } catch (Exception exception) {
            quoteLookupFailureCount.incrementAndGet();
            apiErrorCount.incrementAndGet();
            recordApiError(exception);
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
        pendingPages.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        inboundRateLimiter.prune(now);
        lastRecvAt.entrySet().removeIf(entry -> now - entry.getValue() >= ephemeralCacheTtlMillis);
        lastMsgIds.keySet().removeIf(conversationId -> !lastRecvAt.containsKey(conversationId));
        receivedAtByMessage.entrySet().removeIf(entry -> now - entry.getValue() >= ephemeralCacheTtlMillis);
        receivedMessages.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        typingTasks.entrySet().removeIf(entry -> entry.getValue().isCancelled() || entry.getValue().isDone());
        streamStates.entrySet().removeIf(entry -> now - entry.getValue().createdAtMillis >= streamStateTtlMillis);
        trimConversationCache();
        trimMessageCache();
        trimQuoteCache();
    }

    private void trimConversationCache() {
        int overflow = lastRecvAt.size() - maxConversationCacheEntries;
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
        int overflow = receivedAtByMessage.size() - maxMessageCacheEntries;
        if (overflow <= 0) {
            return;
        }
        receivedAtByMessage.entrySet().stream()
                .sorted(Comparator.comparingLong(Map.Entry::getValue))
                .limit(overflow)
                .forEach(entry -> receivedAtByMessage.remove(entry.getKey(), entry.getValue()));
    }

    private void trimQuoteCache() {
        int overflow = receivedMessages.size() - maxMessageCacheEntries;
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
                typingKeepaliveMillis, typingKeepaliveMillis, TimeUnit.MILLISECONDS);
        typingTasks.put(userId, task);
    }

    private void stopTyping(String userId) {
        ScheduledFuture<?> task = typingTasks.remove(userId);
        if (task != null) task.cancel(true);
    }

    private void sendInputNotifyQuietly(String userId) {
        try {
            ensureToken();
            RestClient client = buildRestClient(apiBase);
            ObjectNode body = objectMapper.createObjectNode();
            body.put("msg_type", 6);
            ObjectNode notify = body.putObject("input_notify");
            notify.put("input_type", 1);
            notify.put("input_second", inputNotifySeconds);
            body.put("msg_seq", messageSequence.next());
            client.post().uri("/v2/users/{openid}/messages", userId)
                    .header("Authorization", "QQBot " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve().body(String.class);
        } catch (Exception exception) {
            apiErrorCount.incrementAndGet();
            recordApiError(exception);
            // 输入状态失败不影响主回复，但会进入监控
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
                catch (Exception exception) {
                    heartbeatFailureCount.incrementAndGet();
                    recordApiError(exception);
                }
            }
            // 每次心跳顺带体检：半开连接只有这里能发现（socket 的回调不会触发）
            healSilentGateway();
        }, intervalMs / 2, intervalMs / 2, TimeUnit.MILLISECONDS);
    }

    /**
     * 自我修复：连续 {@link #SILENT_INTERVALS} 个心跳周期收不到任何帧就主动关掉旧连接并重连一次。
     *
     * <p>为什么必须做：这种"半开"状态下 OkHttp 既不报错也不回调关闭，只靠告警的话机器人会
     * **一直聋着**，等人看到告警再去重启。现在它能自己恢复（面板短暂显示异常 + 一条告警，
     * 重连成功后告警会再推一条"已恢复"）。
     */
    private void healSilentGateway() {
        if (!gatewayWentSilent() || reconnectPending.get()) {
            return;
        }
        log.warn("QQ 网关连续 {} 个心跳周期没有任何帧，判定为半开连接，主动重连", SILENT_INTERVALS);
        heartbeatFailureCount.incrementAndGet();
        WebSocket stale = ws;
        if (stale != null) {
            // 先把旧 socket 关掉再重连：旧连接可能还在收消息，留着会变成"两条连接同时投递 → 重复回复"
            try { stale.close(1000, "heartbeat timeout"); } catch (Exception ignored) { }
        }
        reconnect();
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
    public boolean supportsProactiveCare(String userId) {
        return !isGroupConversation(userId);
    }

    @Override
    public void sendText(String userId, String text) { sendTextFrom(null, userId, text); }

    @Override
    public void sendTextFrom(String botId, String userId, String text) {
        sendTextResultFrom(botId, userId, text);
    }

    @Override
    public boolean sendTextResultFrom(String botId, String userId, String text) {
        return sendTextReplyResultFrom(botId, userId, null, text);
    }

    @Override
    public boolean hasReliableSendStatus() {
        return true;
    }

    @Override
    public void sendTextReplyFrom(String botId, String userId, String replyToMsgId, String text) {
        sendTextReplyResultFrom(botId, userId, replyToMsgId, text);
    }

    @Override
    public boolean sendTextReplyResultFrom(String botId, String userId, String replyToMsgId, String text) {
        long started = System.nanoTime();
        try {
            boolean sent = sendWithPassiveFirst(userId, replyToMsgId, text);
            return recordTextSend(started, sent);
        } finally {
            if (!isGroupConversation(userId)) {
                stopTyping(userId);
            }
            // 清理未使用的流式会话（确认流程等未走 agent 的回复不会触发 onDone）
            streamStates.remove(userId);
        }
    }

    /**
     * 统一的文本发送计数入口。
     *
     * <p>流式回复（{@code sendStreamFrame}、以及工具脚注那条普通发送）以前没有计入
     * {@code textSendSuccess/Failure}：机器人明明回复成功，面板「QQ 消息 · 发送成功」却一直是 0。
     * 这里把成功/失败与耗时都补上，保证统计口径一致。
     */
    private boolean recordTextSend(long startedNanos, boolean sent) {
        if (sent) {
            textSendSuccessCount.incrementAndGet();
        } else {
            textSendFailureCount.incrementAndGet();
        }
        textSendDurationMillis.addAndGet(Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L));
        return sent;
    }

    @Override
    public boolean sendMediaReplyFrom(String botId, String userId, String replyToMsgId, OutboundMedia media) {
        long started = System.nanoTime();
        boolean chunkedAttempt = false;
        if (isGroupConversation(userId) || media == null || media.localFile() == null || !Files.isRegularFile(media.localFile())) {
            return false;
        }
        try {
            long fileSize = Files.size(media.localFile());
            long outboundLimit = maxOutboundMediaBytes > 0 ? maxOutboundMediaBytes : 20 * 1024 * 1024L;
            if (fileSize <= 0) {
                log.warn("[qq] media send rejected user={} file={} size={} maxBytes={}",
                        userId, media.fileName(), fileSize, outboundLimit);
                return false;
            }
            ensureToken();
            if (fileSize > outboundLimit && fileSize <= 200L * 1024 * 1024) {
                chunkedAttempt = true;
                chunkedMediaUploader.upload(buildRestClient(apiBase), accessToken, userId,
                        media.localFile(), media.fileName(), qqFileType(media.contentType()));
                mediaSendSuccessCount.incrementAndGet();
                mediaSendDurationMillis.addAndGet((System.nanoTime() - started) / 1_000_000L);
                log.info("[qq] chunked media sent user={} file={} size={}", userId, media.fileName(), fileSize);
                return true;
            }
            if (fileSize > outboundLimit) {
                log.warn("[qq] media send rejected user={} file={} size={} maxBytes={}",
                        userId, media.fileName(), fileSize, outboundLimit);
                return false;
            }
            ObjectNode body = objectMapper.createObjectNode();
            body.put("file_type", qqFileType(media.contentType()));
            body.put("file_data", Base64.getEncoder().encodeToString(Files.readAllBytes(media.localFile())));
            body.put("file_name", media.fileName());
            body.put("srv_send_msg", true);
            RestClient client = buildRestClient(apiBase);
            client.post().uri("/v2/users/{openid}/files", userId)
                    .header("Authorization", "QQBot " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve().body(String.class);
            log.info("[qq] uploaded local media user={} file={}", userId, media.fileName());
            mediaSendSuccessCount.incrementAndGet();
            return true;
        } catch (Exception exception) {
            mediaSendFailureCount.incrementAndGet();
            if (chunkedAttempt) chunkUploadFailureCount.incrementAndGet();
            apiErrorCount.incrementAndGet();
            recordApiError(exception);
            log.warn("[qq] media send failed user={}: {}", userId, exception.getMessage());
            return false;
        } finally {
            mediaSendDurationMillis.addAndGet((System.nanoTime() - started) / 1_000_000L);
        }
    }

    @Override
    public boolean deleteMessage(String botId, String userId, String messageId) {
        if (isGroupConversation(userId) || userId == null || userId.isBlank()
                || messageId == null || messageId.isBlank()) return false;
        try {
            ensureToken();
            buildRestClient(apiBase).delete().uri("/v2/users/{openid}/messages/{messageId}", userId, messageId)
                    .header("Authorization", "QQBot " + accessToken)
                    .retrieve().toBodilessEntity();
            log.info("[qq] deleted bot message user={} messageId={}", userId, messageId);
            return true;
        } catch (Exception exception) {
            apiErrorCount.incrementAndGet();
            recordApiError(exception);
            log.warn("[qq] delete message failed user={} messageId={} reason={}", userId, messageId, exception.getMessage());
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

    private static long bounded(long value, long minimum, long maximum, long fallback) {
        return value < minimum || value > maximum ? fallback : value;
    }

    private static int bounded(int value, int minimum, int maximum, int fallback) {
        return value < minimum || value > maximum ? fallback : value;
    }

    private static long[] parseBackoff(String value) {
        if (value == null || value.isBlank()) {
            return new long[]{2_000L, 5_000L, 10_000L, 30_000L};
        }
        java.util.ArrayList<Long> values = new java.util.ArrayList<>();
        for (String part : value.split(",")) {
            try {
                long parsed = Long.parseLong(part.trim());
                if (parsed >= 250 && parsed <= 600_000) {
                    values.add(parsed);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (values.isEmpty()) {
            return new long[]{2_000L, 5_000L, 10_000L, 30_000L};
        }
        long[] result = new long[values.size()];
        for (int index = 0; index < values.size(); index++) {
            result[index] = values.get(index);
        }
        return result;
    }

    /**
     * 优先被动回复（带 msg_id，私聊 60 分钟窗口内有效）；失败则降级主动消息。
     * 被动消息每用户每天 1000 条上限且未认证频控 5/qp、30/qpm —— 主动仅作兜底。
     */
    private boolean sendWithPassiveFirst(String userId, String replyToMsgId, String text) {
        int effectiveLimit = Math.max(256, maxMessageChars);
        if (text != null && text.length() > effectiveLimit) {
            return sendLongMessage(userId, replyToMsgId, text);
        }
        boolean useMarkdown = looksLikeMarkdown(text);
        return sendPassive(userId, replyToMsgId, text, useMarkdown);
    }

    private boolean sendLongMessage(String userId, String replyToMsgId, String text) {
        int limit = Math.max(256, maxMessageChars);
        List<String> parts = new java.util.ArrayList<>();
        int offset = 0;
        while (offset < text.length()) {
            int end = Math.min(text.length(), offset + limit);
            if (end < text.length()) {
                int breakAt = Math.max(offset + 1, text.lastIndexOf('\n', end));
                if (breakAt > offset + limit / 2) end = breakAt;
            }
            String part = text.substring(offset, end).trim();
            if (!part.isBlank()) parts.add(part);
            offset = end;
        }
        if (parts.isEmpty()) return true;
        List<String> labeledPages = new java.util.ArrayList<>();
        for (int index = 0; index < parts.size(); index++) {
            labeledPages.add("[" + (index + 1) + "/" + parts.size() + "]\n" + parts.get(index));
        }
        String firstPage = labeledPages.getFirst();
        if (labeledPages.size() > 1) {
            firstPage += "\n\n发送“继续”查看下一页。";
        }
        if (!sendPassive(userId, replyToMsgId, firstPage, looksLikeMarkdown(firstPage))) {
            log.warn("[qq] long message delivery failed user={} part=1 total={}", userId, parts.size());
            return false;
        }
        if (labeledPages.size() > 1) {
            long expiresAt = System.currentTimeMillis() + ephemeralCacheTtlMillis;
            pendingPages.put(userId, new PendingPages(List.copyOf(labeledPages.subList(1, labeledPages.size())), expiresAt));
            pendingPages.entrySet().stream()
                    .sorted(Comparator.comparingLong(entry -> entry.getValue().expiresAt()))
                    .limit(Math.max(0, pendingPages.size() - maxConversationCacheEntries))
                    .forEach(entry -> pendingPages.remove(entry.getKey(), entry.getValue()));
            log.info("[qq] long message paged user={} remainingPages={}", userId, labeledPages.size() - 1);
        }
        return true;
    }

    /** 粗略判断文本是否含 Markdown 语法（标题/加粗/列表/引用/代码/分隔线） */
    private boolean looksLikeMarkdown(String text) {
        if (text == null || text.isBlank()) return false;
        if (text.length() > markdownMaxChars) return false;
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

    private boolean sendPassive(String userId, String replyToMsgId, String text, boolean markdown) {
        boolean withinWindow = false;
        try {
            pruneEphemeralCaches(System.currentTimeMillis());
            ensureToken();
            String targetMsgId = replyToMsgId == null || replyToMsgId.isBlank() ? lastMsgIds.get(userId) : replyToMsgId;
            Long recvAt = replyToMsgId == null || replyToMsgId.isBlank()
                    ? lastRecvAt.get(userId) : receivedAtByMessage.get(replyCacheKey(userId, replyToMsgId));
            withinWindow = targetMsgId != null
                    && recvAt != null
                    && (System.currentTimeMillis() - recvAt) < passiveWindowMillis;
            if (!withinWindow) {
                return sendProactive(userId, text, markdown);
            }
            rememberBotMessageId(userId, postMessage(userId, text, markdown, targetMsgId));
            log.info("[qq] send(passive) -> {} ({} chars)", userId, text == null ? 0 : text.length());
            return true;
        } catch (Exception e) {
            apiErrorCount.incrementAndGet();
            recordApiError(e);
            return handleSendFailure(userId, text, markdown, withinWindow, e);
        }
    }

    /**
     * 主动消息（不带 msg_id）：QQ 平台侧对主动消息有配额，所以这里既记账、也受
     * {@code qq.proactive-daily-limit} 约束。超过上限时**放弃发送并记 WARN**（带当天计数），
     * 而不是发出去等平台拒绝——那样只会得到一条看不懂的接口错误。
     */
    private boolean sendProactive(String userId, String text, boolean markdown) {
        if (!proactiveQuotaAllows(userId)) {
            return false;
        }
        ensureToken();
        rememberBotMessageId(userId, postMessage(userId, text, markdown, null));
        if (proactiveQuota != null) {
            proactiveQuota.record();
        }
        log.info("[qq] send(proactive) -> {} ({} chars{})", userId, text == null ? 0 : text.length(), quotaSuffix());
        return true;
    }

    private boolean proactiveQuotaAllows(String userId) {
        if (proactiveQuota == null || proactiveQuota.allows()) {
            return true;
        }
        log.warn("[qq] 主动消息已达当日上限（今日 {}/{}），放弃发送 user={}", proactiveQuota.used(),
                proactiveQuota.limit(), userId);
        return false;
    }

    /** 日志里的当天主动消息计数；账本不可用（Redis 异常）时只说明情况，不显示 "-1/0" 这种噪声 */
    private String quotaSuffix() {
        if (proactiveQuota == null) {
            return "";
        }
        long used = proactiveQuota.used();
        if (used < 0) {
            return "，主动额度账本不可用";
        }
        int limit = proactiveQuota.limit();
        return "，今日主动 " + used + (limit > 0 ? "/" + limit : "") + " 条";
    }

    /**
     * 发送失败后的处置（这里修的是"同一条消息发两遍"）。
     *
     * <p>能不能重发，取决于**这次失败到底有没有把消息发出去**：
     * <ul>
     *   <li>响应体里带了消息 id：QQ 其实已经发出去了（只是响应本身报错）→ 先撤掉它，重发不会重复；</li>
     *   <li>4xx：服务端**明确拒收**（msg_id 失效、被动窗口过期、令牌失效、限频）→ 没发出去，重发安全；</li>
     *   <li>超时、连接中断、5xx：**结果未知**，请求可能已经送达。原来的代码在这里同样会降级重发，
     *       于是用户收到两条一模一样的消息。现在按"已发出"处理——被动回复返回 true，让上层不要
     *       再走标准回复路径；宁可偶尔少一条，也不要重复刷屏。次数记在 {@code sendResultUnknown} 上，面板可见。</li>
     * </ul>
     */
    private boolean handleSendFailure(String userId, String text, boolean markdown, boolean wasPassive, Exception cause) {
        String orphanMessageId = extractMessageId(cause);
        if (!orphanMessageId.isBlank()) {
            deleteMessage(null, userId, orphanMessageId);
        } else if (!isDefiniteRejection(cause)) {
            sendResultUnknownCount.incrementAndGet();
            log.warn("[qq] {}发送结果未知（{}），不再重发以免重复 user={}{}",
                    wasPassive ? "被动" : "主动", cause.getClass().getSimpleName(), userId, quotaSuffix());
            return wasPassive;
        }
        log.warn("[qq] {}发送被拒 user={}: {} → 重发一次主动消息{}", wasPassive ? "被动" : "主动", userId,
                cause.getMessage(), quotaSuffix());
        try {
            return sendProactive(userId, text, markdown);
        } catch (Exception retryFailure) {
            apiErrorCount.incrementAndGet();
            recordApiError(retryFailure);
            log.warn("[qq] 重发失败 user={}: {}", userId, retryFailure.getMessage());
            return false;
        }
    }

    /** 服务端明确拒收（4xx）＝ 这条消息没有被受理，重发不会产生重复 */
    private boolean isDefiniteRejection(Throwable error) {
        return error instanceof RestClientResponseException response
                && response.getStatusCode().is4xxClientError();
    }

    private String extractMessageId(Exception exception) {
        if (!(exception instanceof RestClientResponseException response)) return "";
        String body = response.getResponseBodyAsString();
        if (body == null || body.isBlank()) return "";
        try {
            JsonNode node = objectMapper.readTree(body);
            for (String field : List.of("id", "message_id", "messageId")) {
                String value = node.path(field).asText("");
                if (!value.isBlank()) return value;
            }
            JsonNode data = node.path("data");
            if (data.isObject()) {
                for (String field : List.of("id", "message_id", "messageId")) {
                    String value = data.path(field).asText("");
                    if (!value.isBlank()) return value;
                }
            }
        } catch (Exception ignored) {
            // Error bodies are not guaranteed to be JSON.
        }
        return "";
    }

    // Sends a QQ message and optionally attaches the passive reply target.
    private String postMessage(String conversationId, String text, boolean markdown, String replyToMsgId) {
        RestClient client = buildRestClient(apiBase);
        ObjectNode body = buildMessageBody(text, markdown, replyToMsgId);
        return client.post().uri(messagePath(conversationId), targetOpenid(conversationId))
                .header("Authorization", "QQBot " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .retrieve().body(String.class);
    }

    private void rememberBotMessageId(String userId, String response) {
        if (response == null || response.isBlank()) return;
        try {
            String messageId = objectMapper.readTree(response).path("id").asText("");
            if (!messageId.isBlank()) lastBotMsgIds.put(userId, messageId);
        } catch (Exception ignored) {
        }
    }

    // Builds the request payload shared by passive and proactive QQ sends.
    private ObjectNode buildMessageBody(String text, boolean markdown, String replyToMsgId) {
        ObjectNode body = objectMapper.createObjectNode();
        if (markdown) {
            body.putObject("markdown").put("content", text);
            body.put("msg_type", 2);
        } else {
            body.put("content", text);
            body.put("msg_type", 0);
        }
        if (replyToMsgId != null && !replyToMsgId.isBlank()) {
            body.put("msg_id", replyToMsgId);
            body.put("msg_seq", messageSequence.next());
        }
        return body;
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
                && (System.currentTimeMillis() - recvAt) < passiveWindowMillis;
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
                boolean toolFooter = finalContent.contains("\n\n> _调用工具：");
                if (toolFooter && !state.hasPartialContent) {
                    log.info("[qq] sending tool footer through standard markdown user={}", userId);
                    long startedFooter = System.nanoTime();
                    boolean sent = recordTextSend(startedFooter, sendWithPassiveFirst(userId, passiveMsgId, finalContent));
                    state.failed = !sent;
                    state.done = sent;
                    streamStates.remove(userId);
                    return;
                }
                log.info("[qq] stream final frame user={} toolFooter={} len={}", userId,
                        toolFooter, finalContent.length());
                long startedFinal = System.nanoTime();
                boolean sent = recordTextSend(startedFinal, sendStreamFrame(userId, passiveMsgId, state, finalContent, 10));
                state.failed = !sent;
                state.done = sent;
                streamStates.remove(userId);
                if (sent) {
                    log.info("[qq] stream session done user={}", userId);
                } else {
                    removeIncompleteStream(userId, state);
                    log.warn("[qq] stream final delivery failed; orchestrator will use standard reply user={}", userId);
                }
            }

            @Override
            public void onError(Throwable t) {
                state.failed = true;
                removeIncompleteStream(userId, state);
                streamStates.remove(userId);
                log.warn("[qq] stream failed user={}: {}", userId, t.getMessage());
            }

            @Override
            public boolean isDone() {
                return state.done && !state.failed;
            }
        };
    }

    private void removeIncompleteStream(String userId, StreamSessionState state) {
        String streamMessageId = state == null ? null : state.streamMsgId;
        if (streamMessageId == null || streamMessageId.isBlank()) return;
        if (!deleteMessage(null, userId, streamMessageId)) {
            log.warn("[qq] unable to remove incomplete stream user={} messageId={}", userId, streamMessageId);
        }
    }

    /** 发送一帧流式消息（append 模式：content_raw 为增量）；首帧成功后记录 stream_msg_id */
    private boolean sendStreamFrame(String userId, String msgId, StreamSessionState state, String contentRaw, int inputState) {
        try {
            ensureToken();
            RestClient client = buildRestClient(apiBase);
            ObjectNode body = buildStreamBody(msgId, state, contentRaw, inputState);
            String resp = client.post().uri("/v2/users/{openid}/stream_messages", userId)
                    .header("Authorization", "QQBot " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve().body(String.class);
            updateStreamMessageId(state, resp);
            log.info("[qq] stream frame user={} state={} idx={} len={}", userId, inputState, state.index - 1, contentRaw.length());
            return true;
        } catch (Exception e) {
            state.failed = true;
            apiErrorCount.incrementAndGet();
            recordApiError(e);
            log.warn("[qq] stream frame failed user={}: {}", userId, e.getMessage());
            return false;
        }
    }

    // Builds one append-mode payload for a QQ streaming reply.
    private ObjectNode buildStreamBody(String msgId, StreamSessionState state, String contentRaw, int inputState) {
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
        return body;
    }

    // Stores the stream identifier returned by QQ after the first successful frame.
    private void updateStreamMessageId(StreamSessionState state, String response) throws Exception {
        if (state.streamMsgId != null || response == null) {
            return;
        }
        JsonNode node = objectMapper.readTree(response);
        String id = node.path("id").asText("");
        if (!id.isBlank()) {
            state.streamMsgId = id;
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
        inboundRateLimiter.clear();
        WebSocket s = ws;
        if (s != null) {
            try { s.close(1000, "shutdown"); } catch (Exception ignored) { }
        }
        closeWebSocketClient();
    }
}
