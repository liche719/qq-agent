package com.liche.wechatagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime policies for the QQ transport; protocol constants remain in the channel implementation. */
@ConfigurationProperties(prefix = "qq")
public class QqRuntimeProperties {

    public static final String DEFAULT_USER_AGENT = "qqbot-nodejs/1.0.4";
    public static final String DEFAULT_RECONNECT_BACKOFF_MS = "2000,5000,10000,30000";
    public static final long DEFAULT_RECONNECT_DELAY_MS = 2_000L;
    public static final int DEFAULT_TOKEN_EXPIRE_SECONDS = 7_200;
    public static final long DEFAULT_TOKEN_REFRESH_LEAD_MS = 60_000L;
    public static final int DEFAULT_HEARTBEAT_MS = 45_000;
    public static final int DEFAULT_INPUT_NOTIFY_SECONDS = 60;
    public static final int DEFAULT_MARKDOWN_MAX_CHARS = 4_000;
    public static final int DEFAULT_INBOUND_RATE_LIMIT_PER_MINUTE = 20;

    private String userAgent = DEFAULT_USER_AGENT;
    private String reconnectBackoffMs = DEFAULT_RECONNECT_BACKOFF_MS;
    private long reconnectDelayMs = DEFAULT_RECONNECT_DELAY_MS;
    private int tokenDefaultExpireSeconds = DEFAULT_TOKEN_EXPIRE_SECONDS;
    private long tokenRefreshLeadMs = DEFAULT_TOKEN_REFRESH_LEAD_MS;
    private int defaultHeartbeatMs = DEFAULT_HEARTBEAT_MS;
    private int inputNotifySeconds = DEFAULT_INPUT_NOTIFY_SECONDS;
    private int markdownMaxChars = DEFAULT_MARKDOWN_MAX_CHARS;

    /** 入站按用户限流（每分钟条数）；0 = 关闭。防止刷屏把每用户串行队列与模型额度占满。 */
    private int inboundRateLimitPerMinute = DEFAULT_INBOUND_RATE_LIMIT_PER_MINUTE;
    /** 主动消息每天最多发多少条；0 = 不限（只记账、只在面板与日志里体现）。 */
    private int proactiveDailyLimit = 0;

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        if (userAgent != null && !userAgent.isBlank()) {
            this.userAgent = userAgent.trim();
        }
    }

    public String getReconnectBackoffMs() {
        return reconnectBackoffMs;
    }

    public void setReconnectBackoffMs(String reconnectBackoffMs) {
        if (reconnectBackoffMs != null && !reconnectBackoffMs.isBlank()) {
            this.reconnectBackoffMs = reconnectBackoffMs.trim();
        }
    }

    public long getReconnectDelayMs() {
        return reconnectDelayMs;
    }

    public void setReconnectDelayMs(long reconnectDelayMs) {
        this.reconnectDelayMs = reconnectDelayMs;
    }

    public int getTokenDefaultExpireSeconds() {
        return tokenDefaultExpireSeconds;
    }

    public void setTokenDefaultExpireSeconds(int tokenDefaultExpireSeconds) {
        this.tokenDefaultExpireSeconds = tokenDefaultExpireSeconds;
    }

    public long getTokenRefreshLeadMs() {
        return tokenRefreshLeadMs;
    }

    public void setTokenRefreshLeadMs(long tokenRefreshLeadMs) {
        this.tokenRefreshLeadMs = tokenRefreshLeadMs;
    }

    public int getDefaultHeartbeatMs() {
        return defaultHeartbeatMs;
    }

    public void setDefaultHeartbeatMs(int defaultHeartbeatMs) {
        this.defaultHeartbeatMs = defaultHeartbeatMs;
    }

    public int getInputNotifySeconds() {
        return inputNotifySeconds;
    }

    public void setInputNotifySeconds(int inputNotifySeconds) {
        this.inputNotifySeconds = inputNotifySeconds;
    }

    public int getMarkdownMaxChars() {
        return markdownMaxChars;
    }

    public void setMarkdownMaxChars(int markdownMaxChars) {
        this.markdownMaxChars = markdownMaxChars;
    }

    public int getInboundRateLimitPerMinute() {
        return inboundRateLimitPerMinute;
    }

    public void setInboundRateLimitPerMinute(int inboundRateLimitPerMinute) {
        this.inboundRateLimitPerMinute = inboundRateLimitPerMinute;
    }

    public int getProactiveDailyLimit() {
        return proactiveDailyLimit;
    }

    public void setProactiveDailyLimit(int proactiveDailyLimit) {
        this.proactiveDailyLimit = proactiveDailyLimit;
    }
}
